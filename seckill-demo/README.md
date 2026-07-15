# Java秒杀系统学习笔记

## 项目简介
本项目是学习Java秒杀系统的实践项目，通过**渐进式演进**的方式，从最简单的超卖漏洞开始，逐步实现生产级的秒杀架构。

**技术栈**：Spring Boot 3.2.0 + JDK 17 + MyBatis + MySQL

---

## 第一阶段：超卖漏洞演示

### 1.1 为什么这种写法会超卖？

**电影院卖票比喻**：
- 电影院有10个座位（库存=10），10个观众同时在网上买票
- 每个观众看到的座位数都是10（并发读取库存=10）
- 第1个观众买票后，座位数应变为9，但其他9个观众看到的还是10
- 于是10个观众都成功买票，结果卖出了10张票，但座位只有10个，实际没有超卖
- 如果库存是1，10个观众同时看到库存=1，都成功买票，就会卖出10张票，只有1个座位——这就是超卖

**代码中的问题**：
```java
// SeckillServiceImpl.java
Product product = productMapper.selectById(productId);  // 查库存
if (product.getStock() <= 0) {  // 检查库存
    throw new RuntimeException("库存不足");
}
int updateRows = productMapper.decreaseStock(productId);  // 扣库存
```

**并发执行时间线**：
```
线程A：查库存 → stock=1 ✅
线程B：查库存 → stock=1 ✅（线程A还没扣减）
线程A：扣库存 → stock=0 ✅
线程B：扣库存 → stock=-1 ❌ 超卖！
```

### 1.2 SQL建表语句

```sql
-- 商品表
CREATE TABLE product (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL COMMENT '商品名称',
    stock INT NOT NULL DEFAULT 0 COMMENT '库存数量',
    price DECIMAL(10, 2) NOT NULL COMMENT '价格',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品表';

-- 订单表
CREATE TABLE order_info (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL COMMENT '商品ID',
    user_id BIGINT NOT NULL COMMENT '用户ID（模拟）',
    order_no VARCHAR(50) NOT NULL COMMENT '订单号',
    price DECIMAL(10, 2) NOT NULL COMMENT '成交价格',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '订单状态：0-成功，1-失败',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_order_no (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';
```

### 1.3 故意制造超卖漏洞

**ProductMapper.java**（关键代码）：
```java
@Mapper
public interface ProductMapper {
    @Select("SELECT * FROM product WHERE id = #{id}")
    Product selectById(@Param("id") Long id);

    // 故意去掉 stock > 0 条件，制造超卖漏洞
    @Update("UPDATE product SET stock = stock - 1 WHERE id = #{id}")
    int decreaseStock(@Param("id") Long id);
}
```

**注意**：如果SQL是 `WHERE id = #{id} AND stock > 0`，则有隐式防护，不会超卖。

### 1.4 为什么不用隐式防护？

**什么是隐式防护？**
```sql
-- 隐式防护：WHERE条件中加入 stock > 0
UPDATE product SET stock = stock - 1 WHERE id = #{id} AND stock > 0
```

**为什么我们故意去掉它？**

#### 举例说明：电影院卖票的隐式防护

假设电影院有1个座位（stock=1），10个人同时买票：

**有隐式防护的情况**：
```
时间线：
1. 观众A：查座位 → 有1个座位 ✅
2. 观众B：查座位 → 有1个座位 ✅（还没卖出）
3. 观众A：买票 → UPDATE stock=0 WHERE stock>0 → 成功 ✅
4. 观众B：买票 → UPDATE stock=-1 WHERE stock>0 → 失败 ❌（条件不满足）
5. 观众C-J：买票 → 全部失败 ❌
```

**结果**：只卖出1张票，没有超卖！看起来完美？

#### 问题1：用户体验极差

```
库存=1，100人抢购：
1. 100人同时查询 → 都看到有库存
2. 100人同时发起扣减请求
3. 数据库串行执行：
   - 第1个：stock=1 → stock=0，成功
   - 第2-100个：stock=0，WHERE stock>0 不满足 → 全部失败
4. 结果：只有1人成功，99人白忙活
```

**问题**：
- 99个请求**白跑一趟**：查询→判断→扣减→失败
- 数据库执行了**100次UPDATE**，但只有1次有效
- 用户看到"库存不足"时，已经浪费了时间和系统资源

#### 问题2：性能灾难

```
库存=10，1000人抢购：
1. 1000个请求到达服务器
2. 1000次数据库查询（查库存）
3. 1000次数据库UPDATE（扣库存，990次失败）
4. 1000次数据库INSERT（只有10次成功）
5. 总计：3000次数据库操作，只有10次有效
```

**真实场景**：秒杀活动开始，10万请求涌入，数据库直接崩溃。

#### 问题3：业务漏洞

```java
// 当前代码还有业务漏洞
if (product.getStock() <= 0) {
    throw new RuntimeException("库存不足");
}

// 问题：同一用户可以重复抢购！
// 用户A可以同时发送10个请求，抢到10件商品
```

**隐式防护无法解决**：
- 用户重复购买
- 商品限购逻辑
- 复杂业务规则

#### 问题4：无法实现异步处理

**隐式防护的流程**：
```
用户请求 → 数据库查询 → 数据库扣减 → 数据库下单 → 返回结果
```

**生产环境需要**：
```
用户请求 → Redis预扣减 → MQ队列 → 异步处理订单 → 返回"排队中"
```

**隐式防护无法支持这种架构**，因为所有操作都同步阻塞在数据库。

### 1.5 隐式防护 vs 无防护对比

| 场景 | 有隐式防护 | 无隐式防护（我们的代码） |
|------|-----------|------------------------|
| **超卖** | ❌ 不会超卖 | ✅ 会超卖（教学目的） |
| **性能** | ❌ 差（大量无效请求） | ❌ 差（但更明显） |
| **用户体验** | ❌ 差（白忙活） | ❌ 差（直接失败） |
| **业务扩展** | ❌ 困难 | ❌ 困难 |
| **教学价值** | ❌ 低（看不到问题） | ✅ 高（直观理解并发问题） |

### 1.6 我们的教学路径

```
阶段1：无防护（当前）→ 看到超卖问题
阶段2：隐式防护 → 理解为什么不够
阶段3：悲观锁 → 解决超卖但性能差
阶段4：乐观锁 → 读多写少场景
阶段5：Redis预扣减 → 高并发场景
阶段6：Redis+MQ → 生产级架构
```

**关键认知**：隐式防护只是"治标不治本"，我们需要从**架构层面**解决问题，而不是依赖数据库的简单条件判断。

---

## 第二阶段：@Transactional 为什么不能解决超卖？

### 2.1 核心认知

**@Transactional 的作用**：保证方法内的多个数据库操作是原子的（要么全成功，要么全失败）。

**但它不能解决并发问题**，因为：

### 2.2 并发时间线演示

```
1. 线程A：@Transactional开始
2. 线程A：查库存 → stock=1  ✅
3. 线程B：@Transactional开始  
4. 线程B：查库存 → stock=1  ✅（线程A还没扣减，所以B读到的还是1）
5. 线程A：扣库存 → stock=0  ✅
6. 线程A：生成订单 → 成功  ✅
7. 线程A：@Transactional提交
8. 线程B：扣库存 → stock=-1 ❌ 超卖！
9. 线程B：生成订单 → 成功  ❌ 超卖订单！
10. 线程B：@Transactional提交
```

**问题根源**：线程B在线程A扣减之前就读到了旧库存（stock=1），@Transactional 无法阻止这种情况。

### 2.3 验证实验

**实验步骤**：
1. 在 `SeckillServiceImpl.seckill` 方法上添加 `@Transactional`
2. 用JMeter并发测试（库存=10，并发=20）
3. 观察数据库：库存变成负数，订单数超过库存

**实验结果**：超卖依然存在！

---

## 第三阶段：悲观锁解决方案

### 3.1 悲观锁原理

**`SELECT ... FOR UPDATE`** 的作用：
1. 查询时对行加锁
2. 其他线程尝试查询同一行时会**等待**（阻塞）
3. 直到当前线程提交事务释放锁

### 3.2 代码实现

**ProductMapper.java**（添加悲观锁方法）：
```java
@Mapper
public interface ProductMapper {
    @Select("SELECT * FROM product WHERE id = #{id}")
    Product selectById(@Param("id") Long id);

    // 悲观锁：查询时加行锁，其他线程等待
    @Select("SELECT * FROM product WHERE id = #{id} FOR UPDATE")
    Product selectByIdForUpdate(@Param("id") Long id);

    @Update("UPDATE product SET stock = stock - 1 WHERE id = #{id}")
    int decreaseStock(@Param("id") Long id);
}
```

**SeckillServiceImpl.java**（使用悲观锁）：
```java
@Override
@Transactional
public Order seckill(Long productId, Long userId) {
    // 1. 使用悲观锁查询商品信息（SELECT ... FOR UPDATE）
    Product product = productMapper.selectByIdForUpdate(productId);
    if (product == null) {
        throw new RuntimeException("商品不存在");
    }

    // 2. 检查库存
    if (product.getStock() <= 0) {
        throw new RuntimeException("库存不足");
    }

    // 3. 扣减库存
    int updateRows = productMapper.decreaseStock(productId);
    if (updateRows == 0) {
        throw new RuntimeException("库存扣减失败，可能库存不足");
    }

    // 4. 生成订单
    Order order = new Order();
    order.setProductId(productId);
    order.setUserId(userId);
    order.setOrderNo(UUID.randomUUID().toString().replace("-", ""));
    order.setPrice(product.getPrice());
    order.setStatus(0); // 成功
    orderMapper.insert(order);

    return order;
}
```

### 3.3 悲观锁执行时间线

```
1. 线程A：SELECT ... FOR UPDATE → 加锁，查到stock=1
2. 线程B：SELECT ... FOR UPDATE → 等待（被阻塞）
3. 线程A：扣库存 → stock=0
4. 线程A：生成订单
5. 线程A：提交事务 → 释放锁
6. 线程B：获得锁，查到stock=0
7. 线程B：抛出"库存不足"异常
```

### 3.4 悲观锁的优缺点

**优点**：
- 简单可靠，能防止超卖
- 适合写多读少的场景

**缺点**：
- **性能差**：所有请求串行执行，并发能力低
- **死锁风险**：多个锁可能造成死锁
- **不适合高并发**：秒杀场景每秒几万请求，数据库扛不住

---

## 第四阶段：乐观锁解决方案

### 4.1 乐观锁原理

**核心思想**：不加锁，但在更新时检查数据是否被其他线程修改过。

**版本号机制**：
1. 查询时读取 `version` 字段
2. 更新时检查 `version` 是否变化
3. 如果 `version` 变化，说明数据被修改过，需要重试

**SQL实现**：
```sql
-- 更新时检查版本号
UPDATE product 
SET stock = stock - 1, version = version + 1 
WHERE id = #{id} AND version = #{version}
```

### 4.2 代码实现

**ProductMapper.java**（添加乐观锁方法）：
```java
@Mapper
public interface ProductMapper {
    @Select("SELECT * FROM product WHERE id = #{id}")
    Product selectById(@Param("id") Long id);

    @Select("SELECT * FROM product WHERE id = #{id} FOR UPDATE")
    Product selectByIdForUpdate(@Param("id") Long id);

    @Update("UPDATE product SET stock = stock - 1 WHERE id = #{id}")
    int decreaseStock(@Param("id") Long id);

    // 乐观锁：更新时检查版本号
    @Update("UPDATE product SET stock = stock - 1, version = version + 1 WHERE id = #{id} AND version = #{version}")
    int decreaseStockByVersion(@Param("id") Long id, @Param("version") Integer version);
}
```

**SeckillServiceImpl.java**（乐观锁版本）：
```java
@Override
public Order seckillV4乐观锁(Long productId, Long userId) {
    int maxRetry = 3;  // 最大重试次数

    for (int i = 0; i < maxRetry; i++) {
        // 1. 查询商品信息（普通查询，不加锁）
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new RuntimeException("商品不存在");
        }

        // 2. 检查库存
        if (product.getStock() <= 0) {
            throw new RuntimeException("库存不足");
        }

        // 3. 乐观锁扣减库存
        int updateRows = productMapper.decreaseStockByVersion(
            productId, 
            product.getVersion()
        );

        if (updateRows > 0) {
            // 4. 扣减成功，生成订单
            Order order = new Order();
            order.setProductId(productId);
            order.setUserId(userId);
            order.setOrderNo(UUID.randomUUID().toString().replace("-", ""));
            order.setPrice(product.getPrice());
            order.setStatus(0);
            orderMapper.insert(order);
            return order;
        }

        // 版本冲突，重试
        try {
            Thread.sleep(10);  // 短暂休眠，减少冲突
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    throw new RuntimeException("秒杀失败，请重试");
}
```

### 4.3 乐观锁执行时间线

```
1. 线程A：查询商品 → stock=1, version=0
2. 线程B：查询商品 → stock=1, version=0
3. 线程A：更新库存 → UPDATE ... WHERE version=0 → 成功，version变为1
4. 线程B：更新库存 → UPDATE ... WHERE version=0 → 失败（version已变为1）
5. 线程B：重试查询 → stock=0, version=1
6. 线程B：检查库存 → 库存不足，抛出异常
```

### 4.4 乐观锁的优缺点

**优点**：
- **读不加锁**：查询时不加锁，性能好
- **适合读多写少**：冲突少时重试次数少
- **无死锁**：不持有锁，不会死锁

**缺点**：
- **写冲突需要重试**：高并发写时重试次数多
- **ABA问题**：数据可能被改回去（可通过时间戳解决）
- **不适合写多读少**：冲突多时性能下降

### 4.5 数据库表结构更新

**添加version字段**：
```sql
-- 更新product表，添加version字段
ALTER TABLE product ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号' AFTER price;

-- 更新现有数据的version为0
UPDATE product SET version = 0 WHERE version IS NULL;
```

**更新后的表结构**：
```sql
CREATE TABLE product (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL COMMENT '商品名称',
    stock INT NOT NULL DEFAULT 0 COMMENT '库存数量',
    price DECIMAL(10, 2) NOT NULL COMMENT '价格',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品表';
```

### 4.6 测试乐观锁

**接口地址**：
```
GET http://localhost:8080/seckill/v4/1?userId=1001
```

**JMeter测试**：
1. 线程数：20
2. Ramp-Up：0秒
3. 循环次数：1
4. 预期结果：成功订单数 = 库存数，无超卖

---

## 方案对比

| 方案 | 并发能力 | 复杂度 | 适用场景 | 超卖防护 | 性能 | 重试机制 |
|------|---------|--------|----------|----------|------|----------|
| 无锁（原始） | 低 | 低 | 教学演示 | ❌ 无 | 差 | 无 |
| @Transactional | 低 | 低 | 无并发场景 | ❌ 无 | 差 | 无 |
| 悲观锁 | 中 | 中 | 写多读少 | ✅ 有 | 中 | 无 |
| **乐观锁** | 中高 | 中 | 读多写少 | ✅ 有 | 高 | ✅ 有 |
| **Redis预扣减** | **高** | 高 | **秒杀场景** | ✅ 有 | **极高** | 无 |
| **Redis+MQ** | **极高** | 极高 | **生产环境** | ✅ 有 | **极高** | 无 |

---

## 学习路径

### 第一阶段：基础概念（已完成）
- [x] 理解超卖问题的本质
- [x] 验证@Transactional不能解决并发问题
- [x] 实现悲观锁方案
- [x] 实现乐观锁方案

### 第二阶段：进阶方案
- [ ] Redis预扣减
- [ ] 消息队列异步处理

### 第三阶段：生产级架构
- [ ] 限流降级
- [ ] 分布式锁
- [ ] 高可用设计

---

## 测试方法

### 4.1 用Postman手动测试
1. 启动应用：`mvn spring-boot:run`
2. 发送请求：`GET http://localhost:8080/seckill/1?userId=1001`
3. 检查数据库：库存减1，订单增加

### 4.2 用JMeter并发测试
1. **线程组设置**：
   - 线程数：20-50（模拟并发用户）
   - Ramp-Up：**0秒**（让所有线程同时启动）
   - 循环次数：1

2. **添加同步定时器**：
   - 右键线程组 → 定时器 → Synchronizing Timer
   - Group Size：20（与线程数一致）

3. **HTTP请求**：
   - 路径：`/seckill/1?userId=${__Random(1000,9999,)}`

4. **查看结果树**：观察响应

### 4.3 验证超卖
- 检查数据库 `product` 表：库存可能为负数
- 检查 `order_info` 表：订单数可能超过库存

---

## 重要代码片段

### 5.1 实体类
**Product.java**：
```java
public class Product {
    private Long id;
    private String name;
    private Integer stock;
    private BigDecimal price;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
```

**Order.java**：
```java
public class Order {
    private Long id;
    private Long productId;
    private Long userId;
    private String orderNo;
    private BigDecimal price;
    private Integer status;  // 0-成功，1-失败
    private LocalDateTime createTime;
}
```

### 5.2 Mapper接口
**ProductMapper.java**：
```java
@Mapper
public interface ProductMapper {
    @Select("SELECT * FROM product WHERE id = #{id}")
    Product selectById(@Param("id") Long id);

    @Select("SELECT * FROM product WHERE id = #{id} FOR UPDATE")
    Product selectByIdForUpdate(@Param("id") Long id);

    @Update("UPDATE product SET stock = stock - 1 WHERE id = #{id}")
    int decreaseStock(@Param("id") Long id);
}
```

**OrderMapper.java**：
```java
@Mapper
public interface OrderMapper {
    @Insert("INSERT INTO order_info (product_id, user_id, order_no, price, status) VALUES (#{productId}, #{userId}, #{orderNo}, #{price}, #{status})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Order order);
}
```

### 5.3 Service层
**SeckillService.java**：
```java
public interface SeckillService {
    Order seckillV1(Long productId, Long userId);      // 原始版本
    Order seckillV2(Long productId, Long userId);      // @Transactional版本
    Order seckillV3悲观锁(Long productId, Long userId);  // 悲观锁版本
    Order seckillV4乐观锁(Long productId, Long userId);  // 乐观锁版本
}
```

**SeckillServiceImpl.java**（核心方法）：
```java
@Service
public class SeckillServiceImpl implements SeckillService {
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private OrderMapper orderMapper;

    // 方案1：原始版本 - 有超卖漏洞
    @Override
    public Order seckillV1(Long productId, Long userId) {
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new RuntimeException("商品不存在");
        }
        if (product.getStock() <= 0) {
            throw new RuntimeException("库存不足");
        }
        int updateRows = productMapper.decreaseStock(productId);
        if (updateRows == 0) {
            throw new RuntimeException("库存扣减失败");
        }
        Order order = new Order();
        order.setProductId(productId);
        order.setUserId(userId);
        order.setOrderNo(UUID.randomUUID().toString().replace("-", ""));
        order.setPrice(product.getPrice());
        order.setStatus(0);
        orderMapper.insert(order);
        
        return order;
    }
}
```

### 5.4 Controller层
**SeckillController.java**：
```java
@RestController
@RequestMapping("/seckill")
public class SeckillController {
    @Autowired
    private SeckillService seckillService;

    @GetMapping("/{productId}")
    public Map<String, Object> seckill(@PathVariable Long productId,
                                       @RequestParam(defaultValue = "1") Long userId) {
        Map<String, Object> result = new HashMap<>();
        try {
            Order order = seckillService.seckill(productId, userId);
            result.put("code", 200);
            result.put("message", "秒杀成功");
            result.put("data", order);
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", e.getMessage());
        }
        return result;
    }
}
```

---

## 常见问题

### Q1: 为什么数据库防护（WHERE stock > 0）不够？
**A**: 虽然能防止库存变负数，但：
1. **性能问题**：所有请求直接打到数据库，高并发时数据库崩溃
2. **用户体验差**：库存=0时，100个请求都要等数据库执行完才知道失败
3. **业务漏洞**：无法处理用户重复购买等复杂场景

### Q2: 为什么@Transactional不能解决超卖？
**A**: @Transactional只保证原子性，不能解决并发读问题。多个线程可以同时读到旧库存。

### Q3: 悲观锁有什么缺点？
**A**: 
1. 性能差：所有请求串行执行
2. 死锁风险：多个锁可能造成死锁
3. 不适合高并发：秒杀场景每秒几万请求，数据库扛不住

### Q4: 下一步应该学什么？
**A**: 
1. 乐观锁（版本号机制）：适合读多写少
2. Redis预扣减：高性能，并发能力强
3. 消息队列：异步处理，提升用户体验

---

## 环境配置

### 6.1 application.properties
```properties
spring.datasource.url=jdbc:mysql://localhost:3306/seckill?useSSL=false&serverTimezone=UTC
spring.datasource.username=root
spring.datasource.password=
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver
mybatis.configuration.map-underscore-to-camel-case=true
mybatis.configuration.log-impl=org.apache.ibatis.logging.stdout.StdOutImpl
server.port=8080
```

### 6.2 pom.xml（Spring Boot 3.2.0 + JDK 17）
```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.2.0</version>
</parent>
<properties>
    <java.version>17</java.version>
</properties>
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.mybatis.spring.boot</groupId>
        <artifactId>mybatis-spring-boot-starter</artifactId>
        <version>3.0.3</version>
    </dependency>
    <dependency>
        <groupId>com.mysql</groupId>
        <artifactId>mysql-connector-j</artifactId>
        <scope>runtime</scope>
    </dependency>
</dependencies>
```

---

## 总结

通过本项目，我们学习了：
1. **超卖问题的本质**：并发读导致数据不一致
2. **@Transactional的局限性**：只能保证原子性，不能解决并发问题
3. **悲观锁的原理和实现**：通过数据库锁保证数据一致性，但性能差
4. **乐观锁的原理和实现**：通过版本号机制，读不加锁，性能好
5. **各种方案的优缺点**：为后续学习Redis方案打下基础

**当前进度**：
- ✅ 第一阶段完成：超卖问题、@Transactional、悲观锁、乐观锁
- 🔄 第二阶段：Redis预扣减（高并发方案）

**下一步**：实现Redis预扣减方案，解决高并发场景下的性能问题。

---

*最后更新时间：2026-07-15*
