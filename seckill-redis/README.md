# Java秒杀系统学习笔记 - 第二阶段

## 项目简介
本项目是学习Java秒杀系统的第二阶段，引入Redis解决超卖问题并防止用户重复购买。

**技术栈**：Spring Boot 3.2.0 + JDK 17 + MyBatis + MySQL + Redis

**与第一阶段的区别**：
- 第一阶段：数据库层面解决超卖（悲观锁、乐观锁）
- 第二阶段：Redis预扣减，性能更高，支持高并发

---

## 1. 【原理讲解】为什么Redis decrement能解决超卖？

### 1.1 Redis单线程原子性

**Redis为什么是单线程？**
- Redis是基于内存的操作，CPU不是瓶颈
- 瓶颈在网络I/O和内存操作
- 单线程避免了锁竞争，简化了实现

**原子性怎么理解？**
```
线程A：decrement stock → 执行过程中，其他线程必须等待
线程B：decrement stock → 等待线程A完成
线程C：decrement stock → 等待线程B完成
```

**比喻**：银行ATM机
- 一台ATM机（单线程）
- 同时只能一个人操作
- 操作过程不会被打断
- 保证账户余额的准确性

### 1.2 decrement为什么能解决超卖？

```java
// Redis的decrement是原子操作
Long stock = stringRedisTemplate.opsForValue().decrement(stockKey);
```

**执行过程**：
```
1. Redis接收到 decrement 命令
2. Redis是单线程，串行执行
3. 读取当前值 → 减1 → 写回 → 返回结果
4. 整个过程不会被其他命令打断
```

**与数据库对比**：
```
数据库（多线程）：
1. 线程A：SELECT stock=1
2. 线程B：SELECT stock=1（并发读）
3. 线程A：UPDATE stock=0
4. 线程B：UPDATE stock=-1（超卖！）

Redis（单线程）：
1. 线程A：decrement → stock=0（原子操作）
2. 线程B：decrement → stock=-1（但我们在代码中检查了<0）
3. 代码判断stock<0，回滚并返回售罄
```

### 1.3 为什么需要回滚？

```java
if (stock < 0) {
    // 库存不足，回滚Redis（加回去）
    stringRedisTemplate.opsForValue().increment(stockKey);
    throw new RuntimeException("已售罄");
}
```

**原因**：
- decrement先执行，再检查结果
- 如果不回滚，库存会变成负数
- 回滚后，其他用户仍有机会购买

---

## 2. 【Redis配置类】

```java
package com.example.seckill.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // 设置key的序列化器
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        // 设置value的序列化器
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());

        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
```

**为什么需要配置序列化？**
- 默认使用JDK序列化，读取时需要同类型
- 使用JSON序列化，人可读，兼容性好
- StringRedisTemplate使用String序列化，适合简单字符串

---

## 3. 【改造后的Service代码】

```java
@Override
public Order seckill(Long productId, Long userId) {
    // ==================== 1. 用户重复购买检查 ====================
    String userBuyKey = USER_BUY_KEY_PREFIX + productId + ":" + userId;
    // setIfAbsent：如果key不存在则设置，返回true；如果已存在则返回false
    Boolean isFirstBuy = stringRedisTemplate.opsForValue().setIfAbsent(userBuyKey, "1", 24, TimeUnit.HOURS);
    
    if (Boolean.FALSE.equals(isFirstBuy)) {
        throw new RuntimeException("您已经参与过该商品的秒杀，请勿重复购买");
    }
    // ==================== 用户标记结束 ====================

    // ==================== 2. Redis原子扣减库存 ====================
    String stockKey = STOCK_KEY_PREFIX + productId;
    // decrement是原子操作：将key的值减1，返回减后的值
    Long stock = stringRedisTemplate.opsForValue().decrement(stockKey);
    
    if (stock == null) {
        // Redis中没有该商品的库存，重新加载
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new RuntimeException("商品不存在");
        }
        // 初始化库存到Redis
        stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(product.getStock()));
        // 重新扣减
        stock = stringRedisTemplate.opsForValue().decrement(stockKey);
    }
    
    if (stock < 0) {
        // 库存不足，回滚Redis（加回去）
        stringRedisTemplate.opsForValue().increment(stockKey);
        // 删除用户购买标记，允许其他用户购买
        stringRedisTemplate.delete(userBuyKey);
        throw new RuntimeException("已售罄");
    }
    // ==================== 原子扣减结束 ====================

    // ==================== 3. 生成订单 ====================
    Order order = new Order();
    order.setProductId(productId);
    order.setUserId(userId);
    order.setOrderNo(UUID.randomUUID().toString().replace("-", ""));
    
    // 获取商品价格
    Product product = productMapper.selectById(productId);
    order.setPrice(product.getPrice());
    order.setStatus(0); // 成功
    
    // 保存订单到数据库
    orderMapper.insert(order);
    
    // 同步扣减数据库库存（可选，也可异步）
    productMapper.updateStock(productId, stock.intValue());
    
    return order;
}
```

### 3.1 核心代码解析

**用户重复购买检查**：
```java
String userBuyKey = "user:buy:" + productId + ":" + userId;
Boolean isFirstBuy = stringRedisTemplate.opsForValue().setIfAbsent(userBuyKey, "1", 24, TimeUnit.HOURS);
```
- `setIfAbsent`：原子操作，设置key-value
- 如果key已存在，返回false，不覆盖
- 设置24小时过期，自动清除标记

**Redis原子扣减库存**：
```java
Long stock = stringRedisTemplate.opsForValue().decrement(stockKey);
```
- `decrement`：原子操作，将值减1
- 返回减后的值
- 如果key不存在，先初始化为0再减1

**库存不足回滚**：
```java
if (stock < 0) {
    stringRedisTemplate.opsForValue().increment(stockKey);
    stringRedisTemplate.delete(userBuyKey);
    throw new RuntimeException("已售罄");
}
```
- `increment`：原子操作，将值加1（回滚）
- 删除用户购买标记，允许其他用户购买

---

## 4. 【初始化库存的代码】

### 4.1 使用CommandLineRunner（推荐）

```java
package com.example.seckill.init;

import com.example.seckill.entity.Product;
import com.example.seckill.mapper.ProductMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 项目启动时，将数据库中的商品库存加载到Redis
 * 使用CommandLineRunner，在Spring容器启动完成后执行
 */
@Component
public class StockInitializer implements CommandLineRunner {

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final String STOCK_KEY_PREFIX = "product:stock:";

    @Override
    public void run(String... args) throws Exception {
        System.out.println("========== 开始初始化商品库存到Redis ==========");
        
        List<Product> products = productMapper.selectAll();
        
        for (Product product : products) {
            String stockKey = STOCK_KEY_PREFIX + product.getId();
            String currentStock = stringRedisTemplate.opsForValue().get(stockKey);
            
            if (currentStock == null) {
                stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(product.getStock()));
                System.out.printf("商品[%s]库存初始化到Redis: %d%n", product.getName(), product.getStock());
            } else {
                System.out.printf("商品[%s]库存已存在Redis: %s，跳过%n", product.getName(), currentStock);
            }
        }
        
        System.out.println("========== 商品库存初始化完成 ==========");
    }
}
```

### 4.2 CommandLineRunner vs @PostConstruct

| 特性 | CommandLineRunner | @PostConstruct |
|------|-------------------|----------------|
| **执行时机** | Spring容器完全启动后 | Bean初始化完成后 |
| **依赖注入** | 已完成 | 可能未完成 |
| **多Bean执行** | 按Order注解排序 | 无序 |
| **推荐场景** | 启动后初始化任务 | Bean内部初始化 |

### 4.3 使用@PostConstruct（备选）

```java
@Component
public class StockInitializer {

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @PostConstruct
    public void init() {
        // 初始化逻辑同上
    }
}
```

---

## 5. 【检验标准】

### 5.1 JMeter测试配置

1. **线程组设置**：
   - 线程数：100
   - Ramp-Up：0秒
   - 循环次数：1

2. **HTTP请求**：
   - 路径：`/seckill/1?userId=1001`（同一用户ID）

3. **同步定时器**：
   - Group Size：100

### 5.2 验证点

| 检查项 | 预期结果 | 验证方法 |
|--------|---------|----------|
| **库存不为负** | stock >= 0 | `GET product:stock:1` in Redis |
| **不重复下单** | 每个用户只有1个订单 | `SELECT COUNT(*) FROM order_info WHERE user_id=1001` |
| **成功订单数** | <= 初始库存 | 统计返回code=200的请求数 |

### 5.3 测试步骤

```bash
# 1. 重置数据库库存
UPDATE product SET stock = 10 WHERE id = 1;

# 2. 清除Redis中的库存key
DEL product:stock:1

# 3. 重启应用，等待初始化完成

# 4. 运行JMeter测试

# 5. 检查结果
# Redis库存
GET product:stock:1  # 应该 >= 0

# 数据库订单数
SELECT COUNT(*) FROM order_info WHERE product_id = 1;  # 应该 <= 10

# 数据库库存
SELECT stock FROM product WHERE id = 1;  # 应该与Redis一致
```

---

## 6. 【手动作业】

### 问题场景

Redis扣库存成功后，如果后续发MQ或写数据库失败，库存已经扣了怎么办？

### 需要思考的问题

1. **库存不一致**：Redis扣了1，但订单没创建成功
2. **用户标记已存在**：用户下次无法购买
3. **数据丢失**：Redis数据和数据库数据不一致

### 你需要实现的方案

设计一个"回滚补偿"方案，处理以下场景：
- 场景A：Redis扣库存成功，写数据库失败
- 场景B：Redis扣库存成功，发MQ失败
- 场景C：Redis扣库存成功，用户标记已设置，但后续流程失败

### 提示方向

1. **事务补偿**：数据库事务回滚
2. **定时任务**：定期检查不一致数据
3. **消息队列**：异步重试
4. **状态机**：订单状态流转

### 参考解法：四种补偿方案

#### 方案1：事务补偿（实时补偿）

**核心思想**：Redis扣库存和数据库操作放在同一个"逻辑事务"中，失败时立即回滚。

```java
@Override
@Transactional
public Order seckillWithCompensation(Long productId, Long userId) {
    // 1. 用户重复购买检查
    // 2. Redis扣库存
    // 3. 生成订单（数据库操作）
    try {
        orderMapper.insert(order);
        productMapper.updateStock(productId, stock.intValue());
        return order;
    } catch (Exception e) {
        // 4. 失败时回滚Redis
        stringRedisTemplate.opsForValue().increment(stockKey);
        stringRedisTemplate.delete(userBuyKey);
        throw new RuntimeException("秒杀失败，请重试", e);
    }
}
```

**优点**：实现简单，实时补偿
**缺点**：回滚也失败时数据不一致

#### 方案2：定时任务补偿（异步补偿）

**核心思想**：定期检查Redis和数据库的不一致数据，进行补偿。

```java
@Scheduled(fixedRate = 5000)
public void checkAndCompensate() {
    // 1. 检查库存一致性
    // 2. 检查待处理订单
    // 3. 补偿不一致数据
}
```

**优点**：支持异步操作，最终一致性
**缺点**：有延迟，实现复杂

#### 方案3：状态机补偿（断点重试）

**核心思想**：使用订单状态跟踪处理进度，支持状态流转和重试。

```java
// 订单状态：INIT -> STOCK_DEDUCTED -> DB_SAVED -> COMPLETED
stringRedisTemplate.opsForValue().set(orderStatusKey, String.valueOf(STATUS_STOCK_DEDUCTED));
// ... 后续操作
stringRedisTemplate.opsForValue().set(orderStatusKey, String.valueOf(STATUS_DB_SAVED));
```

**优点**：支持断点重试，状态清晰
**缺点**：实现复杂，需要额外字段

#### 方案4：消息队列补偿（异步解耦）

**核心思想**：使用MQ异步处理订单，支持重试和死信队列。

```java
// 扣库存后发送MQ消息
stringRedisTemplate.opsForList().leftPush(ORDER_QUEUE_KEY, orderJson);

// 消费者异步处理
@EventListener
public void consumeOrderMessage(String orderJson) {
    // 处理订单
}
```

**优点**：解耦、削峰、自动重试
**缺点**：需要MQ中间件，数据最终一致性

### 方案选择建议

| 场景 | 推荐方案 | 原因 |
|------|---------|------|
| **简单业务** | 方案1：事务补偿 | 实现简单，实时补偿 |
| **异步操作** | 方案2：定时任务 | 支持MQ，最终一致性 |
| **复杂流程** | 方案3：状态机 | 支持断点重试，易于调试 |
| **高并发** | 方案4：消息队列 | 解耦、削峰，适合生产环境 |

### 测试补偿方案

```bash
# 方案1：事务补偿
GET /compensation/v1/1?userId=1001

# 方案2：定时任务补偿
GET /compensation/v2/1?userId=1001

# 方案3：状态机补偿
GET /compensation/v3/1?userId=1001

# 方案4：消息队列补偿
GET /compensation/v4/1?userId=1001
```

---

## 方案对比

### 第一阶段 vs 第二阶段

| 对比项 | 第一阶段（数据库锁） | 第二阶段（Redis预扣减） |
|--------|---------------------|------------------------|
| **并发能力** | 低（串行执行） | **高（Redis单线程但极快）** |
| **性能** | 差（多次数据库操作） | **好（内存操作）** |
| **超卖防护** | ✅ 有 | ✅ 有 |
| **重复购买** | ❌ 未处理 | ✅ 处理 |
| **复杂度** | 低 | 中 |
| **数据一致性** | 强一致 | 最终一致 |

### Redis方案的优缺点

**优点**：
- 性能极高：Redis单线程，每秒10万+QPS
- 原子操作：decrement天然防超卖
- 用户标记：setIfAbsent天然防重复

**缺点**：
- 数据一致性：Redis和数据库可能不一致
- 需要初始化：启动时加载库存
- 内存限制：库存数据占用Redis内存

---

## 常见问题

### Q1: 为什么Redis用String而不是Hash？
**A**: 
- String简单，适合单个值
- Hash适合多个字段
- 这里库存是单个值，用String足够

### Q2: 为什么setIfAbsent要设置过期时间？
**A**: 
- 防止用户标记永久存在
- 24小时后自动清除，允许用户下次活动购买
- 避免Redis内存无限增长

### Q3: 为什么decrement后要检查<0？
**A**: 
- decrement先执行，再检查结果
- 如果库存=1，两个请求同时decrement
- 第一个返回0，第二个返回-1
- 需要回滚第二个请求的扣减

### Q4: 数据库库存和Redis库存不一致怎么办？
**A**: 
- 这是正常现象，最终一致性
- 定时任务同步数据
- 以Redis为准，数据库作为持久化

---

## 环境配置

### application.properties
```properties
spring.datasource.url=jdbc:mysql://localhost:3306/seckill?useSSL=false&serverTimezone=UTC
spring.datasource.username=root
spring.datasource.password=
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver

# Redis配置
spring.data.redis.host=localhost
spring.data.redis.port=6379
spring.data.redis.password=
spring.data.redis.database=0

server.port=8080
```

### Redis安装

**Windows**：
```bash
# 下载Redis for Windows
# https://github.com/tporadowski/redis/releases
# 安装后启动
redis-server
```

**Linux/Mac**：
```bash
# 安装Redis
sudo apt install redis-server  # Ubuntu
brew install redis  # Mac

# 启动Redis
redis-server
```

---

## 总结

通过第二阶段，我们学习了：
1. **Redis原子操作**：decrement天然防超卖
2. **用户重复购买**：setIfAbsent天然防重复
3. **库存初始化**：CommandLineRunner在启动时加载
4. **数据一致性**：Redis和数据库的最终一致性
5. **补偿方案**：四种补偿方案处理数据不一致

**补偿方案总结**：
- **方案1：事务补偿** - 实时补偿，简单直接
- **方案2：定时任务** - 异步补偿，最终一致性
- **方案3：状态机** - 断点重试，状态清晰
- **方案4：消息队列** - 异步解耦，适合高并发

**当前进度**：
- ✅ 第一阶段完成：超卖问题、@Transactional、悲观锁、乐观锁
- ✅ 第二阶段完成：Redis预扣减、用户重复购买、补偿方案

**下一步**：可以继续学习分布式锁、限流降级等高级主题。

---

*最后更新时间：2026-07-16*
