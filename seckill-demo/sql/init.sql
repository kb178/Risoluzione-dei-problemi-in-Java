-- 创建数据库
CREATE DATABASE IF NOT EXISTS seckill;
USE seckill;

-- 商品表（包含乐观锁版本号）
CREATE TABLE IF NOT EXISTS product (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL COMMENT '商品名称',
    stock INT NOT NULL DEFAULT 0 COMMENT '库存数量',
    price DECIMAL(10, 2) NOT NULL COMMENT '价格',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品表';

-- 订单表
CREATE TABLE IF NOT EXISTS order_info (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL COMMENT '商品ID',
    user_id BIGINT NOT NULL COMMENT '用户ID（模拟）',
    order_no VARCHAR(50) NOT NULL COMMENT '订单号',
    price DECIMAL(10, 2) NOT NULL COMMENT '成交价格',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '订单状态：0-成功，1-失败',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_order_no (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

-- 插入测试商品
INSERT INTO product (name, stock, price, version) VALUES ('iPhone 15 Pro', 10, 7999.00, 0);
INSERT INTO product (name, stock, price, version) VALUES ('MacBook Pro 14', 5, 14999.00, 0);
