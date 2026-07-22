# 短链接系统 - 高并发学习项目

## 项目简介

本项目是一个 **短链接系统渐进式学习项目**，通过三个阶段，从最基础的单机版本逐步演进到分布式架构。每个阶段解决上一阶段遗留的问题，形成完整的技术演进路线。

**技术栈**：Spring Boot 3.2.0 + JDK 17 + MyBatis-Plus + MySQL + Redis

---

## 项目结构

```
short-url-system/
├── src/
│   ├── main/
│   │   ├── java/com/shorturl/
│   │   │   ├── ShortUrlApplication.java    # 主启动类
│   │   │   ├── controller/
│   │   │   │   └── ShortUrlController.java  # 控制器
│   │   │   ├── service/
│   │   │   │   └── ShortUrlService.java     # 服务层
│   │   │   ├── entity/
│   │   │   │   └── ShortUrl.java            # 实体类
│   │   │   └── mapper/
│   │   │       └── ShortUrlMapper.java      # Mapper接口
│   │   └── resources/
│   │       ├── application.yml              # 配置文件
│   │       └── init.sql                     # 数据库初始化脚本
│   └── test/                                # 测试目录
├── pom.xml                                  # Maven配置
└── README.md                                # 本文件
```

---

## 第一阶段：基础版（单机MySQL + Redis缓存）

### 目标

实现一个基础的短链接系统，包含以下核心功能：
1. 短链接生成（Base62编码）
2. 短链接跳转（302重定向）
3. Redis缓存优化
4. 点击次数统计

### 架构图

```
用户请求
    ↓
Controller接收请求
    ↓
┌─────────────────────────────────────┐
│           Service层                 │
│                                     │
│  创建短链接流程:                      │
│  1. 检查URL是否已存在                 │
│  2. 生成唯一短码（Base62编码）         │
│  3. 保存到MySQL                      │
│  4. 缓存到Redis                      │
│                                     │
│  查询短链接流程:                      │
│  1. 先查Redis缓存                    │
│  2. 缓存命中 → 直接返回               │
│  3. 缓存未命中 → 查MySQL → 回填Redis  │
│  4. 更新点击次数                      │
└─────────────────────────────────────┘
    ↓
MySQL + Redis存储
```

### 技术实现

#### 1. 短码生成算法 - Base62编码

```java
// Base62字符集
private static final String BASE62_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

/**
 * Base62编码
 * 将数字转换为62进制字符串
 */
private String base62Encode(long num) {
    StringBuilder sb = new StringBuilder();
    while (num > 0) {
        sb.append(BASE62_CHARS.charAt((int) (num % 62)));
        num /= 62;
    }
    // 填充到6位
    while (sb.length() < 6) {
        sb.append('0');
    }
    return sb.reverse().toString();
}
```

**为什么选择Base62？**
- 比Base64更URL友好（不含`+`、`/`、`=`等特殊字符）
- 62个字符：0-9、a-z、A-Z
- 6位短码可表示 62^6 = 568亿种组合

#### 2. Redis缓存策略

```java
// 缓存Key格式
String cacheKey = "short:url:" + shortCode;

// 缓存过期时间：24小时
redisTemplate.opsForValue().set(cacheKey, originalUrl, 24, TimeUnit.HOURS);
```

**缓存策略说明：**
- **写入缓存**：创建短链接时同步写入Redis
- **读取缓存**：查询时先查Redis，命中则直接返回
- **缓存回填**：未命中时查MySQL，然后回填Redis
- **过期时间**：24小时后自动过期

#### 3. 数据库表结构

```sql
CREATE TABLE short_url (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    short_code VARCHAR(10) NOT NULL COMMENT '短码',
    original_url VARCHAR(2048) NOT NULL COMMENT '原始URL',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    click_count BIGINT DEFAULT 0 COMMENT '点击次数',
    deleted TINYINT DEFAULT 0 COMMENT '是否删除',
    UNIQUE KEY uk_short_code (short_code),
    INDEX idx_original_url (original_url(100))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='短链接表';
```

**索引设计：**
- `uk_short_code`：短码唯一索引，保证短码唯一性
- `idx_original_url`：原始URL索引，支持URL查询（前100字符）

### API接口

#### 1. 创建短链接

```bash
POST /api/shorten
Content-Type: application/json

{
    "originalUrl": "https://www.example.com/very/long/url/path?param=value"
}

Response:
{
    "shortCode": "aB3xYz",
    "shortUrl": "http://localhost:8081/aB3xYz",
    "originalUrl": "https://www.example.com/very/long/url/path?param=value"
}
```

#### 2. 短链接跳转

```bash
GET /{shortCode}

# 302重定向到原始URL
Response:
HTTP/1.1 302 Found
Location: https://www.example.com/very/long/url/path?param=value
```

#### 3. 健康检查

```bash
GET /api/health

Response:
{
    "status": "UP",
    "service": "short-url-system"
}
```

### 测试方法

#### 1. 初始化数据库

```bash
mysql -u root -p < src/main/resources/init.sql
```

#### 2. 启动应用

```bash
cd short-url-system
mvn spring-boot:run
```

#### 3. 测试接口

```bash
# 创建短链接
curl -X POST http://localhost:8081/api/shorten \
  -H "Content-Type: application/json" \
  -d '{"originalUrl": "https://www.baidu.com"}'

# 访问短链接（会跳转到百度）
curl -v http://localhost:8081/aB3xYz
```

### 教学价值

1. **理解短链接核心原理**：如何将长URL转换为短码
2. **掌握Base62编码**：比Base64更适合URL的编码方式
3. **理解缓存策略**：读多写少场景的缓存设计
4. **学习数据库设计**：唯一索引、索引优化

### 已知问题（第二阶段解决）

1. **短码冲突**：使用数据库自增ID，可能出现冲突
2. **缓存穿透**：恶意请求不存在的短码
3. **缓存雪崩**：大量缓存同时过期
4. **单点故障**：单机MySQL，无法扩展

---

## 第二阶段：优化版（待实现）

**计划功能：**
- 布隆过滤器防止缓存穿透
- 互斥锁防止缓存击穿
- 随机过期时间防止缓存雪崩
- 分布式ID生成（雪花算法）

---

## 第三阶段：分布式版（待实现）

**计划功能：**
- 分库分表
- 读写分离
- 分布式部署
- 监控告警

---

## 环境要求

- JDK 17+
- MySQL 8.0+
- Redis 6.0+
- Maven 3.6+

---

*最后更新时间：2026-07-22*
