# Risoluzione dei problemi in Java - Java秒杀系统渐进式学习

## 项目简介

本项目是一个 **Java秒杀系统渐进式学习项目**，通过三个子项目，从最简单的超卖漏洞开始，逐步演进到生产级的秒杀架构。每个阶段解决上一阶段遗留的问题，形成完整的技术演进路线。

**技术栈**：Spring Boot 3.2.0 + JDK 17 + MyBatis + MySQL + Redis + RabbitMQ + Redisson

---

## 项目结构

```
Risoluzione-dei-problemi-in-Java/
├── seckill-demo/          # 第一阶段：数据库层面解决超卖
├── seckill-redis/         # 第二阶段：Redis预扣减 + 补偿方案
├── seckill-rabbitmq/      # 第三阶段：MQ异步 + 分布式锁 + 生产级架构
└── README.md              # 本文件
```

---

## 三个阶段对比

| 特性 | seckill-demo | seckill-redis | seckill-rabbitmq |
|------|-------------|---------------|------------------|
| **核心方案** | 数据库锁 | Redis预扣减 | Lua原子脚本 + MQ异步 |
| **超卖防护** | 悲观锁/乐观锁 | Redis原子性 | Lua脚本原子性 |
| **防重复购买** | ❌ | ✅ Redis标记 | ✅ Lua脚本原子标记 |
| **异步处理** | ❌ | ❌ 同步写DB | ✅ MQ异步写DB |
| **死信队列** | ❌ | ❌ | ✅ |
| **限流** | ❌ | ❌ | ✅ Guava RateLimiter |
| **分布式锁** | ❌ | ❌ | ✅ Redisson |
| **队列监控** | ❌ | ❌ | ✅ 自动扩缩容 |
| **数据一致性** | 强一致 | 最终一致 | 最终一致 |
| **并发能力** | 低 | 高 | 极高 |

---

## 第一阶段：seckill-demo（数据库锁方案）

**目标**：理解超卖问题的本质，掌握数据库锁方案

**技术栈**：Spring Boot + MyBatis + MySQL（无Redis、无MQ）

### 包含5种方案

| 方案 | 方法名 | 原理 | 能否防超卖 |
|------|--------|------|-----------|
| V1 原始版 | `seckillV1` | 先查库存再扣减 | ❌ 超卖 |
| V2 事务版 | `seckillV2` | 加@Transactional | ❌ 仍然超卖 |
| V3 悲观锁 | `seckillV3悲观锁` | SELECT ... FOR UPDATE | ✅ 但性能差 |
| V4 乐观锁 | `seckillV4乐观锁` | 版本号机制 | ✅ 性能较好 |
| V5 Redis | `TODO` | 预留 | - |

### 接口

```
GET /seckill/{productId}?userId=xxx
```

### 教学价值

- 理解为什么 @Transactional 不能解决并发问题
- 理解悲观锁 vs 乐观锁的原理和适用场景
- 理解数据库方案在高并发下的性能瓶颈

---

## 第二阶段：seckill-redis（Redis预扣减方案）

**目标**：引入Redis解决高并发问题，学习数据一致性补偿

**技术栈**：Spring Boot + MyBatis + MySQL + Redis

### 核心改进

- Redis `decrement` 原子扣减库存
- `setIfAbsent` 防止用户重复购买
- `CommandLineRunner` 启动时初始化库存到Redis

### 四种补偿方案

| 方案 | 原理 | 适用场景 |
|------|------|---------|
| V1 事务补偿 | @Transactional + catch回滚Redis | 简单业务 |
| V2 定时任务 | 定期检查Redis和DB库存一致性 | 异步操作 |
| V3 状态机 | 订单状态流转 + 断点重试 | 复杂流程 |
| V4 消息队列 | Redis List模拟MQ | 高并发 |

### 接口

```
GET /seckill/{productId}?userId=xxx
GET /compensation/{version}/{productId}?userId=xxx   # 补偿方案
```

### 教学价值

- 理解Redis单线程原子性为什么能防超卖
- 理解Redis和MySQL数据不一致的场景和解决方案
- 为第三阶段MQ方案打下基础

---

## 第三阶段：seckill-rabbitmq（生产级架构）

**目标**：实现完整的生产级秒杀系统

**技术栈**：Spring Boot + MyBatis + MySQL + Redis + RabbitMQ + Redisson

### 架构图

```
用户请求
    ↓
限流（Guava RateLimiter，QPS=100）
    ├─ 令牌足够 → 通过
    └─ 令牌不足 → 拒绝，返回"系统繁忙"
    ↓
Lua脚本扣Redis库存（原子操作：检查库存+扣减+设置用户标记）
    ├─ 用户已购买 → 拒绝
    ├─ 库存不足 → 拒绝
    └─ 成功 → 继续
    ↓
发送MQ消息（RabbitMQ）
    ├─ 发送失败 → 回滚Redis库存和用户标记
    └─ 发送成功 → 返回"排队中"
    ↓
【异步处理】
    ↓
消费者处理（@Transactional + AUTO ACK）
    ├─ 幂等性检查（order_no唯一键 + DuplicateKeyException）
    ├─ 扣减MySQL库存
    ├─ 插入订单表
    └─ 成功 → 自动ack / 失败 → 重试（最多3次，指数退避）
    ↓
重试耗尽 → 死信队列 → 人工处理
```

### 两套秒杀方案

| 接口 | 方案 | 说明 |
|------|------|------|
| `GET /seckill/{productId}?userId=xxx` | Lua脚本 | 无锁，靠Redis原子性 |
| `GET /seckill/v2/{productId}?userId=xxx` | Redisson分布式锁 | 看门狗自动续期 |

### 核心组件

| 组件 | 类 | 作用 |
|------|-----|------|
| Lua原子脚本 | `RedisService` | 一次调用完成：检查库存+扣减+设置用户标记 |
| MQ生产者 | `SeckillServiceImpl` | Redis扣库存后发MQ，失败回滚 |
| MQ消费者 | `OrderConsumer` | 扣MySQL库存+创建订单，AUTO模式+事务 |
| 死信消费者 | `DeadLetterConsumer` | 处理重试多次失败的消息 |
| 限流 | `RateLimitService` + `@RateLimit` | Guava令牌桶，QPS=100 |
| 队列监控 | `QueueMonitorService` | 每10秒检查队列深度，动态扩缩消费者(2-20) |
| 分布式锁 | `SeckillServiceV2Impl` | Redisson RLock，看门狗30秒过期自动续期 |
| 库存初始化 | `StockInitializer` | 启动时从DB加载库存到Redis |
| 事务拦截 | `RabbitMQConfig` | AdviceChain确保@Transactional对@RabbitListener生效 |

### 数据库表结构

```sql
-- 商品表
CREATE TABLE product (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    stock INT NOT NULL DEFAULT 0,
    price DECIMAL(10, 2) NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 订单表（双唯一索引防重）
CREATE TABLE order_info (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    order_no VARCHAR(50) NOT NULL,
    price DECIMAL(10, 2) NOT NULL,
    status TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_order_no (order_no),
    UNIQUE KEY uk_user_product (user_id, product_id)
);
```

### 一致性保证（7层防护）

| 层级 | 机制 | 保证什么 |
|------|------|---------|
| 第1层 | Lua脚本 | Redis操作原子性 |
| 第2层 | @Transactional | MySQL操作原子性（扣库存+创建订单） |
| 第3层 | MQ重试机制 | 消费失败自动重试，最多3次 |
| 第4层 | 死信队列 | 重试多次失败，保存待人工处理 |
| 第5层 | 定时任务补偿 | 每30秒检查库存一致性 |
| 第6层 | 数据库唯一索引 | uk_order_no + uk_user_product 防重复 |
| 第7层 | 限流 | 控制请求速率，保护系统 |

---

## 环境要求

- JDK 17+
- MySQL 8.0+
- Redis 6.0+
- RabbitMQ 3.10+（需启用Management插件）
- Maven 3.6+

## 快速开始

### 1. 初始化数据库

```bash
mysql -u root -p < seckill-rabbitmq/src/main/resources/init.sql
```

### 2. 启动Redis

```bash
redis-server
```

### 3. 启动RabbitMQ

```bash
# Docker方式（推荐）
docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:3-management
```

### 4. 启动应用

```bash
cd seckill-rabbitmq
mvn spring-boot:run
```

### 5. 测试

```bash
# V1（Lua脚本）
curl "http://localhost:8080/seckill/1?userId=1001"

# V2（分布式锁）
curl "http://localhost:8080/seckill/v2/1?userId=1001"

# 队列监控
curl http://localhost:8080/monitor/status
```

### 6. JMeter压测

- 线程数：10000
- 循环次数：10
- Ramp-Up：30秒
- 预期：Redis和MySQL库存一致，订单数正确

---

## 学习路线

```
阶段1（seckill-demo）
  └─ 理解超卖 → @Transactional局限 → 悲观锁 → 乐观锁
        ↓
阶段2（seckill-redis）
  └─ Redis原子扣减 → 防重复购买 → 四种补偿方案
        ↓
阶段3（seckill-rabbitmq）
  └─ Lua原子脚本 → MQ异步削峰 → 死信队列 → 限流 → 分布式锁 → 队列监控
```

## 技术评级

基于JMeter 10000线程 × 10循环 × 30秒测试，Redis和MySQL库存一致，订单数正确：

**Level 4-5（中高级）** — 核心并发问题全部解决，覆盖Lua原子性、MQ异步、死信队列、限流、幂等性、事务、分布式锁、队列监控等关键技术。

---

*最后更新时间：2026-07-21*
