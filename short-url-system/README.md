# 短链接系统 - 第一阶段

## 快速开始

### 1. 环境要求

- JDK 17+
- MySQL 8.0+
- Redis 6.0+
- Maven 3.6+

### 2. 初始化数据库

```bash
# 登录MySQL
mysql -u root -p

# 执行初始化脚本
source src/main/resources/db/init.sql
```

### 3. 启动Redis

```bash
redis-server
```

### 4. 启动应用

```bash
cd short-url-system
mvn spring-boot:run
```

### 5. 测试接口

```bash
# 创建短链接
curl -X POST http://localhost:8081/api/shorten \
  -H "Content-Type: application/json" \
  -d '{"originalUrl": "https://www.baidu.com"}'

# 访问短链接（会跳转到百度）
curl -v http://localhost:8081/test01
```

## 项目结构

```
short-url-system/
├── src/main/java/com/shorturl/
│   ├── ShortUrlApplication.java      # 主启动类
│   ├── controller/
│   │   └── ShortUrlController.java   # 控制器
│   ├── service/
│   │   └── ShortUrlService.java      # 服务层
│   ├── entity/
│   │   └── ShortUrl.java             # 实体类
│   └── mapper/
│       └── ShortUrlMapper.java       # Mapper接口
├── src/main/resources/
│   ├── application.yml               # 配置文件
│   └── db/
│       └── init.sql                  # 数据库初始化脚本
└── pom.xml                           # Maven配置
```

## 技术说明

### 短码生成算法

使用Base62编码，将数据库自增ID转换为6位短码：
- 字符集：0-9、a-z、A-Z（共62个字符）
- 6位短码可表示 62^6 = 568亿种组合

### 缓存策略

- **写入**：创建短链接时同步写入Redis
- **读取**：先查Redis，命中则直接返回
- **回填**：未命中时查MySQL，然后回填Redis
- **过期**：24小时后自动过期

## 详细笔记

详见：[README-SHORT-URL.md](./README-SHORT-URL.md)

## API文档

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/shorten` | POST | 创建短链接 |
| `/{shortCode}` | GET | 短链接跳转 |
| `/api/health` | GET | 健康检查 |
