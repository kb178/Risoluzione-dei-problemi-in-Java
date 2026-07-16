# Java秒杀系统学习笔记 - 第三阶段

## 项目简介
本项目是学习Java秒杀系统的第三阶段，引入RabbitMQ实现订单异步化，提升系统吞吐量。

**技术栈**：Spring Boot 3.2.0 + JDK 17 + MyBatis + MySQL + Redis + RabbitMQ

**与第二阶段的区别**：
- 第二阶段：Redis扣库存 + 同步写数据库
- 第三阶段：Redis扣库存 + **MQ异步写数据库**

---

## 1. 【原理讲解】MQ削峰的原理

### 食堂排队打饭比喻

**没有MQ（同步处理）**：
```
100个学生同时来打饭
      ↓
1个窗口，100人排队
      ↓
每人打饭1分钟
      ↓
最后1个人等99分钟
```

**有MQ（异步处理）**：
```
100个学生同时来打饭
      ↓
取号机（MQ）：每人取号后离开
      ↓
1个窗口：按号叫号
      ↓
学生可以先去干别的事
```

### MQ削峰的核心原理

```
高并发请求 → MQ队列（缓冲） → 消费者（按能力处理） → 数据库
    10万        10万排队         每秒处理1000           稳定
```

**关键点**：
1. **缓冲**：MQ像水库，洪水来了先存着，慢慢放
2. **削峰**：把瞬间高峰削平成平稳流量
3. **异步**：用户不用等数据库操作完成，立即返回

### 对比同步 vs 异步

| 对比项 | 同步处理（第二阶段） | 异步处理（第三阶段） |
|--------|---------------------|---------------------|
| **响应时间** | 慢（要等数据库） | **快（立即返回）** |
| **吞吐量** | 低（受数据库限制） | **高（MQ缓冲）** |
| **用户体验** | 差（等待） | **好（立即反馈）** |
| **数据一致性** | 强一致 | 最终一致 |
| **复杂度** | 低 | 中 |

---

## 2. 【RabbitMQ配置类】

```java
@Configuration
public class RabbitMQConfig {

    // 队列名称
    public static final String ORDER_QUEUE = "seckill.order.queue";
    // 交换机名称
    public static final String ORDER_EXCHANGE = "seckill.order.exchange";
    // 路由键
    public static final String ORDER_ROUTING_KEY = "seckill.order";

    /**
     * 声明队列
     */
    @Bean
    public Queue orderQueue() {
        return QueueBuilder.durable(ORDER_QUEUE).build();
    }

    /**
     * 声明交换机
     */
    @Bean
    public DirectExchange orderExchange() {
        return ExchangeBuilder.directExchange(ORDER_EXCHANGE)
                .durable(true)
                .build();
    }

    /**
     * 绑定队列到交换机
     */
    @Bean
    public Binding orderBinding(Queue orderQueue, DirectExchange orderExchange) {
        return BindingBuilder.bind(orderQueue)
                .to(orderExchange)
                .with(ORDER_ROUTING_KEY);
    }

    /**
     * 配置消息转换器（JSON格式）
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 配置RabbitTemplate使用JSON转换器
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(jsonMessageConverter());
        return rabbitTemplate;
    }
}
```

### 配置说明

| 配置项 | 说明 | 为什么这么配 |
|--------|------|-------------|
| **durable(true)** | 持久化 | 重启后队列不丢失 |
| **DirectExchange** | 直连交换机 | 简单路由，按路由键精确匹配 |
| **Jackson2JsonMessageConverter** | JSON序列化 | 人可读，兼容性好 |
| **acknowledge-mode=manual** | 手动确认 | 消费成功后才确认，保证可靠性 |

---

## 3. 【生产者代码】

```java
@Override
public String seckill(Long productId, Long userId) {
    // 1. 用户重复购买检查
    // 2. Redis原子扣减库存
    
    // 3. 发送MQ消息（异步处理订单）
    String orderNo = UUID.randomUUID().toString().replace("-", "");
    Product product = productMapper.selectById(productId);
    
    // 构建消息
    OrderMessage message = new OrderMessage(productId, userId, orderNo, product.getPrice());
    
    // 发送消息到MQ
    rabbitTemplate.convertAndSend(
        RabbitMQConfig.ORDER_EXCHANGE,
        RabbitMQConfig.ORDER_ROUTING_KEY,
        message
    );
    
    System.out.printf("订单[%s]已发送到MQ队列%n", orderNo);

    // 4. 立即返回"排队中"
    return "排队中，订单号：" + orderNo;
}
```

### 生产者核心点

1. **消息实体**：`OrderMessage`，包含订单必要信息
2. **发送方法**：`convertAndSend(exchange, routingKey, message)`
3. **立即返回**：不等待消费者处理，提升响应速度

---

## 4. 【消费者代码】

```java
@Component
public class OrderConsumer {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private ProductMapper productMapper;

    @RabbitListener(queues = RabbitMQConfig.ORDER_QUEUE)
    @Transactional
    public void handleOrder(OrderMessage message, Channel channel, 
                           @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            // 1. 幂等性检查（防止重复消费）
            int count = orderMapper.countByUserAndProduct(message.getUserId(), message.getProductId());
            if (count > 0) {
                System.out.printf("用户[%d]已购买过，跳过重复消费%n", message.getUserId());
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 2. 扣减MySQL库存
            int updateRows = productMapper.decreaseStock(message.getProductId());
            if (updateRows == 0) {
                System.out.printf("商品[%d]库存不足%n", message.getProductId());
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 3. 插入订单表
            Order order = new Order();
            order.setProductId(message.getProductId());
            order.setUserId(message.getUserId());
            order.setOrderNo(message.getOrderNo());
            order.setPrice(message.getPrice());
            order.setStatus(0);
            orderMapper.insert(order);

            // 4. 确认消息
            channel.basicAck(deliveryTag, false);

        } catch (Exception e) {
            // 拒绝消息，重新入队重试
            channel.basicNack(deliveryTag, false, true);
        }
    }
}
```

### 消费者核心点

1. **幂等性检查**：防止重复消费
2. **手动确认**：`channel.basicAck()`，处理成功后才确认
3. **异常处理**：`channel.basicNack()`，失败时重新入队

---

## 5. 【数据库唯一索引】

### 给order表加什么索引来防重？

```sql
-- 订单表（添加唯一索引防重）
CREATE TABLE IF NOT EXISTS order_info (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL COMMENT '商品ID',
    user_id BIGINT NOT NULL COMMENT '用户ID（模拟）',
    order_no VARCHAR(50) NOT NULL COMMENT '订单号',
    price DECIMAL(10, 2) NOT NULL COMMENT '成交价格',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '订单状态：0-成功，1-失败',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_order_no (order_no),           -- 订单号唯一
    UNIQUE KEY uk_user_product (user_id, product_id)  -- 用户+商品唯一
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';
```

### 两个唯一索引的作用

| 索引 | 作用 | 防止什么问题 |
|------|------|-------------|
| **uk_order_no** | 订单号唯一 | 防止同一订单重复插入 |
| **uk_user_product** | 用户+商品唯一 | **防止同一用户重复购买** |

### 为什么需要uk_user_product？

```
场景：同一用户发两个请求
1. 请求1：Redis扣库存成功，发送MQ消息1
2. 请求2：Redis扣库存成功，发送MQ消息2（重复购买）
3. 消费者处理消息1：插入订单成功
4. 消费者处理消息2：插入订单失败（uk_user_product冲突）
```

**效果**：数据库层面防止重复购买，即使MQ重复消费也能保证数据一致。

---

## 6. 【检验标准】

### 如何验证MQ确实削峰了？

#### 对比加MQ前后的接口响应时间

**测试方法**：
```bash
# 1. 测试同步版本（第二阶段）
# 接口：/seckill/1?userId=1001
# 记录响应时间：约200-500ms

# 2. 测试异步版本（第三阶段）
# 接口：/seckill/1?userId=1001
# 记录响应时间：约10-50ms
```

**JMeter测试配置**：
1. 线程数：100
2. Ramp-Up：0秒
3. 循环次数：1

**预期结果**：

| 指标 | 同步版本 | 异步版本 | 提升 |
|------|---------|---------|------|
| **平均响应时间** | 300ms | 30ms | **10倍** |
| **吞吐量（QPS）** | 333 | 3333 | **10倍** |
| **数据库连接数** | 100并发 | 10并发 | **10倍** |

#### 验证MQ队列

```bash
# 1. 发送100个请求
# 2. 检查RabbitMQ管理界面
#    - 队列消息数：应该逐渐减少
#    - 消费速率：应该稳定
# 3. 检查数据库
#    - 订单数：应该等于成功请求数
#    - 库存：应该正确扣减
```

---

## 7. 【手动作业】

### 问题场景

如果消费者挂掉了，消息积压在队列里怎么办？

### 需要思考的问题

1. **消息积压**：消费者处理速度跟不上生产速度
2. **消费者宕机**：消费者挂掉，消息无法处理
3. **监控告警**：如何知道队列积压了？

### 你需要设计的方案

设计一个"监控队列深度+自动扩容消费者"的方案：

#### 提示方向

1. **监控队列深度**
   - RabbitMQ Management API
   - 定时任务获取队列深度
   - 阈值告警

2. **自动扩容消费者**
   - 动态创建消费者实例
   - Kubernetes HPA
   - 线程池动态扩容

3. **消息积压处理**
   - 临时扩容消费者
   - 消息转发到其他队列
   - 批量消费

### 参考架构

```
监控层：
  - 定时任务：每10秒检查队列深度
  - 告警：队列深度 > 1000 时告警
  - 扩容：队列深度 > 5000 时自动扩容

执行层：
  - 消费者线程池：核心10，最大50
  - 动态扩容：根据队列深度调整线程数
  - 缩容：队列深度 < 100 时自动缩容

补偿层：
  - 消息转发：积压严重时转发到其他队列
  - 批量消费：一次消费多条消息
  - 人工干预：极端情况人工处理
```

### 参考解法：监控队列深度+自动扩容

#### 已实现的代码

**QueueMonitorService.java**：
```java
@Service
public class QueueMonitorService {

    // 配置参数
    private static final int ALERT_THRESHOLD = 1000;      // 告警阈值
    private static final int SCALE_UP_THRESHOLD = 5000;    // 扩容阈值
    private static final int SCALE_DOWN_THRESHOLD = 100;   // 缩容阈值
    private static final int MAX_CONSUMERS = 20;           // 最大消费者
    private static final int MIN_CONSUMERS = 2;            // 最小消费者
    
    private int currentConsumerCount = 2;
    private ThreadPoolExecutor consumerThreadPool;

    /**
     * 定时任务：每10秒检查一次队列深度
     */
    @Scheduled(fixedRate = 10000)
    public void monitorQueueDepth() {
        // 1. 获取队列深度
        int queueDepth = getQueueDepth();
        
        // 2. 判断是否需要扩容/缩容
        if (queueDepth > SCALE_UP_THRESHOLD && currentConsumerCount < MAX_CONSUMERS) {
            scaleUp(queueDepth);  // 扩容
        } else if (queueDepth < SCALE_DOWN_THRESHOLD && currentConsumerCount > MIN_CONSUMERS) {
            scaleDown(queueDepth);  // 缩容
        }
        
        // 3. 判断是否需要告警
        if (queueDepth > ALERT_THRESHOLD) {
            handleAlert(queueDepth);  // 告警
        }
    }

    /**
     * 扩容消费者
     */
    private void scaleUp(int queueDepth) {
        int targetCount = Math.min(currentConsumerCount + 2, MAX_CONSUMERS);
        System.out.printf("触发扩容：队列深度=%d, 当前=%d, 目标=%d%n", 
            queueDepth, currentConsumerCount, targetCount);
        
        // 启动新消费者
        for (int i = currentConsumerCount; i < targetCount; i++) {
            startNewConsumer();
        }
        
        currentConsumerCount = targetCount;
        saveConsumerCount();
    }

    /**
     * 缩容消费者
     */
    private void scaleDown(int queueDepth) {
        int targetCount = Math.max(currentConsumerCount - 2, MIN_CONSUMERS);
        System.out.printf("触发缩容：队列深度=%d, 当前=%d, 目标=%d%n", 
            queueDepth, currentConsumerCount, targetCount);
        
        currentConsumerCount = targetCount;
        saveConsumerCount();
    }

    /**
     * 处理告警
     */
    private void handleAlert(int queueDepth) {
        System.out.printf("⚠️ 告警：队列积压！深度=%d, 消费者=%d%n", queueDepth, currentConsumerCount);
        // 实际项目中发送告警通知（邮件、短信、钉钉等）
    }
}
```

**QueueMonitorController.java**：
```java
@RestController
@RequestMapping("/monitor")
public class QueueMonitorController {

    @Autowired
    private QueueMonitorService queueMonitorService;

    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> status = queueMonitorService.getStatus();
        result.put("code", 200);
        result.put("data", status);
        return result;
    }
}
```

#### 工作流程

```
定时任务（每10秒）
    ↓
获取队列深度
    ↓
┌─────────────────────────────────────┐
│ 深度 > 5000？                        │
│   是 → 扩容消费者（+2）              │
│   否 → 继续判断                      │
├─────────────────────────────────────┤
│ 深度 < 100？                         │
│   是 → 缩容消费者（-2）              │
│   否 → 继续判断                      │
├─────────────────────────────────────┤
│ 深度 > 1000？                        │
│   是 → 发送告警通知                  │
│   否 → 正常                          │
└─────────────────────────────────────┘
```

#### 验证方法

```bash
# 1. 查看队列状态
curl http://localhost:8080/monitor/status

# 2. 模拟高并发（用JMeter发送大量请求）
# 观察控制台日志，应该看到扩容日志

# 3. 等待队列消费完
# 观察控制台日志，应该看到缩容日志
```

### 参考解法：已实现的优化

#### 1. 死信队列（Dead Letter Queue）

**什么是死信队列？**

死信队列（DLQ）是专门用来存放"死信"消息的队列。

**什么消息会变成"死信"？**
1. **消息被拒绝**：`basicNack` 或 `basicReject`，且 `requeue=false`
2. **消息过期**：队列设置了TTL，消息超时未消费
3. **队列溢出**：队列达到最大长度，新消息进入死信队列

**死信队列的作用：**
```
正常流程：
生产者 → 正常队列 → 消费者 → 处理成功 → 确认

异常流程：
生产者 → 正常队列 → 消费者 → 处理失败 → 拒绝(requeue=false) → 死信队列
                                                      ↓
                                              人工排查/自动重试/记录日志
```

**项目中的实现：**

```java
// RabbitMQConfig.java - 配置死信队列
@Bean
public Queue orderQueue() {
    Map<String, Object> args = new HashMap<>();
    // 设置死信交换机
    args.put("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE);
    // 设置死信路由键
    args.put("x-dead-letter-routing-key", DEAD_LETTER_ROUTING_KEY);
    return QueueBuilder.durable(ORDER_QUEUE).withArguments(args).build();
}

// OrderConsumer.java - 重试超限后进入死信队列
if (retryCount < MAX_RETRY_COUNT) {
    message.setRetryCount(retryCount + 1);
    channel.basicNack(deliveryTag, false, false);  // 进入死信队列
} else {
    System.out.printf("消息重试次数超过限制(%d)，进入死信队列%n", MAX_RETRY_COUNT);
    channel.basicNack(deliveryTag, false, false);
}

// DeadLetterConsumer.java - 消费死信队列
@RabbitListener(queues = RabbitMQConfig.DEAD_LETTER_QUEUE)
public void handleDeadLetter(OrderMessage message, Channel channel, long deliveryTag) {
    // 处理死信消息：记录日志、发送告警、人工处理等
    channel.basicAck(deliveryTag, false);
}
```

**死信队列管理界面：**
```
访问 http://localhost:15672
Queues 标签：
- seckill.order.queue        （正常队列）
- seckill.order.dead.queue   （死信队列）
```

#### 2. 定时任务补偿

**什么是定时任务补偿？**

定期检查Redis库存和数据库库存的一致性，发现不一致时进行补偿。

**为什么需要？**
- MQ消息可能丢失
- 消费者可能处理失败
- 需要保证最终一致性

**项目中的实现：**

```java
// CompensationService.java
@Scheduled(fixedRate = 30000)  // 每30秒检查一次
public void checkStockConsistency() {
    // 1. 遍历所有商品
    List<Product> products = productMapper.selectAll();
    
    for (Product product : products) {
        // 2. 获取Redis库存
        String redisStockStr = stringRedisTemplate.opsForValue().get(stockKey);
        int redisStock = Integer.parseInt(redisStockStr);
        int dbStock = product.getStock();
        
        // 3. 对比库存
        if (redisStock != dbStock) {
            // 4. 以Redis为准，更新数据库
            compensateDatabase(product.getId(), redisStock);
        }
    }
}
```

**定时任务的作用：**
```
MQ流程：生产者 → 队列 → 消费者 → 处理成功/失败
                     ↓
              如果失败，消息进入死信队列
                     ↓
定时任务：每30秒检查一次库存一致性
                     ↓
发现不一致 → 补偿数据库 → 保证最终一致
```

#### 3. 完整的消息处理流程

```
正常流程：
用户请求 → Redis扣库存 → 发送MQ → 返回"排队中"
                              ↓
                         消费者处理
                              ↓
                         扣MySQL库存 + 插入订单
                              ↓
                         确认消息 → 完成

异常流程1（重试）：
用户请求 → Redis扣库存 → 发送MQ → 返回"排队中"
                              ↓
                         消费者处理失败
                              ↓
                         拒绝消息，重新入队
                              ↓
                         重试（最多3次）

异常流程2（死信）：
用户请求 → Redis扣库存 → 发送MQ → 返回"排队中"
                              ↓
                         消费者处理失败3次
                              ↓
                         进入死信队列
                              ↓
                         人工排查处理

异常流程3（补偿）：
定时任务每30秒检查库存一致性
                              ↓
                         发现Redis库存≠数据库库存
                              ↓
                         补偿数据库 → 保证最终一致
```

#### 4. 验证死信队列

```bash
# 1. 模拟消费者失败
# 修改OrderConsumer，让某些消息处理失败
# 观察消息是否进入死信队列

# 2. 查看RabbitMQ管理界面
# http://localhost:15672
# Queues → seckill.order.dead.queue → Messages

# 3. 查看死信消费者日志
# 应该看到"收到死信消息"的日志
```

---

## RabbitMQ安装

### Windows

```bash
# 1. 下载Erlang
# https://www.erlang.org/downloads

# 2. 下载RabbitMQ
# https://www.rabbitmq.com/install-windows.html

# 3. 启动RabbitMQ
rabbitmq-service start

# 4. 启用管理界面
rabbitmq-plugins enable rabbitmq_management

# 5. 访问管理界面
# http://localhost:15672
# 用户名/密码：guest/guest
```

### Docker（推荐）

```bash
# 启动RabbitMQ
docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:3-management

# 访问管理界面
# http://localhost:15672
# 用户名/密码：guest/guest
```

---

## 方案对比

### 第二阶段 vs 第三阶段

| 对比项 | 第二阶段（同步） | 第三阶段（异步） |
|--------|-----------------|-----------------|
| **响应时间** | 慢 | **快** |
| **吞吐量** | 低 | **高** |
| **用户体验** | 差 | **好** |
| **数据一致性** | 强一致 | 最终一致 |
| **复杂度** | 低 | 中 |
| **可靠性** | 高 | 中（需要确认机制） |

### 同步 vs 异步的选择

| 场景 | 推荐方案 | 原因 |
|------|---------|------|
| **低并发** | 同步 | 简单够用 |
| **高并发** | **异步** | 削峰填谷，保护数据库 |
| **实时性要求高** | 同步 | 需要立即返回结果 |
| **实时性要求不高** | **异步** | 可以接受"排队中" |

---

## 常见问题

### Q1: 为什么要手动确认消息？
**A**: 
- 自动确认：消息发送后立即确认，如果消费失败，消息丢失
- 手动确认：消费成功后才确认，保证消息不丢失

### Q2: 幂等性检查为什么用数据库索引？
**A**: 
- 数据库唯一索引是最终保障
- 即使代码逻辑有漏洞，数据库也能防止重复
- 双重保障：代码层 + 数据库层

### Q3: 消息积压了怎么办？
**A**: 
- 短期：增加消费者实例
- 长期：优化消费逻辑，提升处理速度
- 极端：消息转发到其他队列，临时扩容

### Q4: 消费者挂掉了，消息会丢失吗？
**A**: 
- 不会，消息在队列中持久化
- 消费者重启后会继续消费
- 但要注意：正在处理的消息可能会重复消费

---

## 总结

通过第三阶段，我们学习了：
1. **MQ削峰原理**：用食堂排队打饭比喻
2. **RabbitMQ配置**：队列、交换机、绑定
3. **异步处理**：秒杀接口立即返回，订单异步生成
4. **幂等性**：用数据库唯一索引防止重复消费
5. **消息确认**：手动确认保证消息可靠性
6. **死信队列**：处理重试多次仍然失败的消息
7. **定时任务补偿**：定期检查库存一致性，保证最终一致

### 项目架构总结

```
用户请求
    ↓
Redis扣库存（同步，原子操作）
    ↓
发送MQ消息（异步）
    ↓
立即返回"排队中"
    ↓
消费者处理（异步）
    ├─ 成功 → 扣MySQL库存 + 插入订单 → 确认消息
    └─ 失败 → 重试（最多3次）→ 超限进入死信队列
    ↓
定时任务（每30秒）
    ↓
检查Redis库存 vs 数据库库存
    ↓
发现不一致 → 补偿数据库 → 保证最终一致
```

### 重要知识点：一致性分析

#### Redis内部：强一致（Lua脚本保证）

```lua
-- Lua脚本：原子操作
-- 一次执行完成：检查库存 + 扣减库存 + 设置用户标记
redis.call('decr', stockKey)
redis.call('set', userBuyKey, '1')
redis.call('expire', userBuyKey, 86400)
```

**为什么Lua脚本能保证强一致？**
- Redis是单线程
- Lua脚本在Redis中原子执行
- 执行过程中不会被其他命令打断

#### Redis vs MySQL：最终一致（需要补偿）

```
场景：Redis扣库存成功，但MySQL扣库存失败

Redis库存 = 9（已扣）
MySQL库存 = 10（没扣）
→ 不一致！

解决：定时任务补偿
→ 每30秒检查一次
→ 以Redis为准，更新MySQL
→ 保证最终一致
```

#### 为什么不用强一致（分布式事务）？

| 方案 | 一致性 | 性能 | 适用场景 |
|------|--------|------|---------|
| **分布式事务（2PC）** | ✅ 强一致 | ❌ 差（要锁资源） | 银行转账 |
| **TCC** | ✅ 强一致 | ⚠️ 中（要实现3个接口） | 电商下单 |
| **Redis+MQ+补偿** | ❌ 最终一致 | ✅ 好（无锁） | **秒杀场景** |

**秒杀场景为什么选最终一致？**
- 高并发：每秒几万请求
- 高性能：需要毫秒级响应
- 可容忍：几秒延迟用户可接受

#### 一致性总结

```
┌─────────────────────────────────────────────────────┐
│  Lua脚本保证：Redis内部操作原子性                      │
│  └─ 检查库存 + 扣减库存 + 设置用户标记 = 原子操作       │
├─────────────────────────────────────────────────────┤
│  @Transactional保证：MySQL操作原子性                   │
│  └─ 扣库存 + 插入订单 = 原子操作                       │
├─────────────────────────────────────────────────────┤
│  补偿机制保证：Redis和MySQL最终一致性                   │
│  └─ 定时任务每30秒检查，以Redis为准更新MySQL           │
├─────────────────────────────────────────────────────┤
│  结论：                                              │
│  - Redis内部：强一致 ✅                               │
│  - MySQL内部：强一致 ✅                               │
│  - Redis vs MySQL：最终一致 ⚠️                       │
│  - 秒杀场景：最终一致够用 ✅                           │
└─────────────────────────────────────────────────────┘
```

### 数据一致性完整分析（重点！）

#### seckill方法流程

```
用户请求
    ↓
1. Lua脚本扣Redis库存（原子操作）
    ↓
2. 发送MQ消息
    ↓
3. 返回"排队中"
    ↓
【异步处理】
    ↓
4. 消费者扣MySQL库存
    ↓
5. 消费者插入订单
    ↓
6. 确认MQ消息
```

#### 所有不一致场景分析

| 场景 | Redis | MySQL | 问题 | 代码处理 |
|------|-------|-------|------|---------|
| **场景1：MQ发送失败** | 扣了 | 没扣 | 数据不一致 | ✅ 回滚Redis |
| **场景2：MQ消费失败（重试后）** | 扣了 | 没扣 | 数据不一致 | ✅ 死信队列+定时补偿 |
| **场景3：扣库存成功，插入订单失败** | 扣了 | 扣了但没订单 | 库存丢失 | ✅ @Transactional |
| **场景4：插入订单成功，扣库存失败** | 扣了 | 有订单但没扣 | 超卖 | ✅ @Transactional |
| **场景5：消费者处理中宕机** | 扣了 | 处理一半 | 数据不一致 | ✅ 事务回滚+重试 |

#### 场景1：MQ发送失败

```
时间线：
1. Lua脚本扣Redis库存 → 成功（库存=9）
2. 发送MQ消息 → 失败（网络超时）
3. 结果：Redis库存=9，MySQL库存=10（不一致）

解决方案：
try {
    rabbitTemplate.convertAndSend(...);
} catch (Exception e) {
    redisService.rollback(stockKey, userBuyKey);  // 回滚Redis
    throw new RuntimeException("系统繁忙，请稍后重试");
}
```

#### 场景2：MQ消费失败

```
时间线：
1. Lua脚本扣Redis库存 → 成功（库存=9）
2. 发送MQ消息 → 成功
3. 消费者收到消息 → 处理失败
4. 重试3次后 → 进入死信队列
5. 结果：Redis库存=9，MySQL库存=10（不一致）

解决方案：
- 重试机制：最多重试3次
- 死信队列：保存失败消息
- 定时补偿：每30秒检查库存一致性
```

#### 场景3：扣库存成功，插入订单失败（最严重！）

```
时间线：
1. Lua脚本扣Redis库存 → 成功（库存=9）
2. 发送MQ消息 → 成功
3. 消费者扣MySQL库存 → 成功（库存=9）
4. 消费者插入订单 → 失败（数据库异常）
5. 结果：Redis库存=9，MySQL库存=9，但没有订单 ❌

问题：库存卖了但没订单（数据丢失）

解决方案：@Transactional
@Transactional
public void handleOrder(...) {
    try {
        productMapper.decreaseStock(productId);  // 扣库存
        orderMapper.insert(order);               // 插入订单
        channel.basicAck(deliveryTag, false);    // 确认消息
    } catch (Exception e) {
        // 事务自动回滚：扣库存也回滚
        channel.basicNack(deliveryTag, false, true);  // 重新入队
    }
}
```

#### 场景4：插入订单成功，扣库存失败

```
时间线：
1. Lua脚本扣Redis库存 → 成功（库存=9）
2. 发送MQ消息 → 成功
3. 消费者插入订单 → 成功
4. 消费者扣MySQL库存 → 失败
5. 结果：Redis库存=9，MySQL库存=10，有订单 ❌

问题：有订单但库存没扣（超卖）

解决方案：@Transactional（同场景3）
- 扣库存和插入订单在同一个事务中
- 任何一个失败，事务回滚
```

#### 场景5：消费者处理中宕机

```
时间线：
1. Lua脚本扣Redis库存 → 成功（库存=9）
2. 发送MQ消息 → 成功
3. 消费者收到消息 → 处理中...
4. 消费者宕机
5. 结果：消息未确认，会重新投递

解决方案：
- 消息未确认（basicAck），RabbitMQ会重新投递
- 消费者重启后会继续处理
- @Transactional保证事务回滚
```

#### 一致性保证机制总结

```
┌─────────────────────────────────────────────────────────────┐
│  第一层：Lua脚本                                              │
│  └─ 保证Redis操作原子性（检查+扣减+标记）                       │
├─────────────────────────────────────────────────────────────┤
│  第二层：@Transactional                                       │
│  └─ 保证MySQL操作原子性（扣库存+插入订单）                      │
├─────────────────────────────────────────────────────────────┤
│  第三层：MQ重试机制                                            │
│  └─ 消费失败自动重试，最多3次                                   │
├─────────────────────────────────────────────────────────────┤
│  第四层：死信队列                                              │
│  └─ 重试多次失败，保存到死信队列，等待人工处理                    │
├─────────────────────────────────────────────────────────────┤
│  第五层：定时任务补偿                                          │
│  └─ 每30秒检查库存一致性，保证最终一致                           │
├─────────────────────────────────────────────────────────────┤
│  第六层：数据库唯一索引                                        │
│  └─ 防止重复订单，保证幂等性                                    │
├─────────────────────────────────────────────────────────────┤
│  第七层：限流（新增！）                                        │
│  └─ 控制请求速率，防止系统被打垮                                │
└─────────────────────────────────────────────────────────────┘
```

### 限流功能（新增）

#### 什么是限流？

限流（Rate Limiting）是控制请求速率的技术，防止系统被高并发请求打垮。

#### 限流算法：令牌桶

```
令牌桶原理：
1. 桶里有固定数量的令牌（如100个）
2. 令牌以固定速率生成（如每秒100个）
3. 每个请求需要获取一个令牌才能通过
4. 没有令牌的请求等待或拒绝

比喻：
- 桶 = 停车场车位
- 令牌 = 车位
- 请求 = 想停车的车
- 限流 = 车位满了，不让进
```

#### 项目中的实现

**1. RateLimitService.java（限流服务）**
```java
@Service
public class RateLimitService {
    
    private final Map<String, RateLimiter> rateLimiterMap = new ConcurrentHashMap<>();
    
    // 尝试获取令牌
    public boolean tryAcquire(String key, double qps) {
        RateLimiter rateLimiter = getRateLimiter(key, qps);
        return rateLimiter.tryAcquire();
    }
}
```

**2. @RateLimit注解（声明式限流）**
```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {
    double qps() default 100;  // 每秒请求数
    String message() default "系统繁忙，请稍后重试";
}
```

**3. RateLimitAspect.java（限流切面）**
```java
@Aspect
@Component
public class RateLimitAspect {
    
    @Around("@annotation(com.example.seckill.annotation.RateLimit)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        // 获取注解信息
        // 尝试获取令牌
        // 被限流则抛出异常
        return joinPoint.proceed();
    }
}
```

**4. Controller使用限流**
```java
@GetMapping("/{productId}")
@RateLimit(qps = 100, message = "秒杀太火爆了，请稍后重试")
public Map<String, Object> seckill(@PathVariable Long productId) {
    // 正常业务逻辑
}
```

#### 限流效果

```
正常情况：
请求 → 获取令牌 → 令牌足够 → 通过 → 处理请求

被限流：
请求 → 获取令牌 → 令牌不足 → 拒绝 → 返回"系统繁忙"
```

#### 验证限流

```bash
# 1. 用JMeter测试
# 线程数：200
# Ramp-Up：0秒
# 循环次数：1

# 2. 预期结果
# - 前100个请求成功
# - 后100个请求被限流，返回"秒杀太火爆了，请稍后重试"

# 3. 查看RabbitMQ
# 消息数量应该 <= 100（被限流的请求不会发送MQ消息）
```

#### 限流配置建议

| 场景 | QPS | 说明 |
|------|-----|------|
| **开发环境** | 10 | 方便测试 |
| **测试环境** | 100 | 模拟生产 |
| **生产环境** | 1000 | 根据服务器性能调整 |

#### 动态调整QPS

```java
// 动态调整某个接口的QPS
rateLimitService.setRate("seckill", 200);  // 调整为200 QPS
```

### 项目架构总结（完整版）

```
用户请求
    ↓
限流（Guava RateLimiter）← 新增！
    ├─ 令牌足够 → 通过
    └─ 令牌不足 → 拒绝，返回"系统繁忙"
    ↓
Lua脚本扣Redis库存（原子操作）
    ↓
发送MQ消息
    ↓
立即返回"排队中"
    ↓
消费者处理（异步）
    ├─ 成功 → 扣MySQL库存 + 插入订单 → 确认消息
    └─ 失败 → 重试（最多3次）→ 超限进入死信队列
    ↓
定时任务（每30秒）
    ↓
检查Redis库存 vs 数据库库存
    ↓
发现不一致 → 补偿数据库 → 保证最终一致
```

### 下一步

可以继续学习：
- 分布式锁
- 监控告警（Prometheus + Grafana）
- 高可用架构

---

*最后更新时间：2026-07-16*
