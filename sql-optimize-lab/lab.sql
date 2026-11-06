-- ============================================================
-- SQL 优化实验室：建库、建表、造数据
-- 跑一次即可（可重复执行，会先删旧表重建）：
--   mysql -h127.0.0.1 -uroot -p --default-character-set=utf8mb4 < lab.sql
-- 造完的数据量：orders 10万行 / users 1万行 / products 100行，磁盘约 30MB
-- 注意连本机 MySQL 要带 -h127.0.0.1，不然默认管道会连到另一套实例上
-- ============================================================

CREATE DATABASE IF NOT EXISTS sql_lab DEFAULT CHARACTER SET utf8mb4;
USE sql_lab;
SET NAMES utf8mb4;

-- ---------- 0. 数字辅助表（只用来造数据，0-9 五个笛卡尔积 = 10 万行）----------
DROP TABLE IF EXISTS digit;
CREATE TABLE digit (d TINYINT PRIMARY KEY);
INSERT INTO digit VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);

-- ---------- 1. 订单表：10 万行 ----------
-- 故意不在 user_id / product_id / create_time 上建索引，留给练习加
-- （uk_order_no 是刻意留的，跟你 seckill 里的 order_info 一个设计：自增 id 当主键，业务单号单独唯一）
DROP TABLE IF EXISTS orders;
CREATE TABLE orders (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_no    CHAR(32)      NOT NULL COMMENT 'MD5 假 UUID，32 位',
  user_id     INT           NOT NULL COMMENT '1000~10999，约 1 万个用户',
  product_id  INT           NOT NULL COMMENT '1~100',
  status      TINYINT       NOT NULL DEFAULT 0 COMMENT '0 待支付 1 已支付',
  price       DECIMAL(10,2) NOT NULL,
  create_time DATETIME      NOT NULL COMMENT '散布在 2026 年 1~10 月',
  UNIQUE KEY uk_order_no (order_no)
) COMMENT '订单表，10万行';

INSERT INTO orders (order_no, user_id, product_id, status, price, create_time)
SELECT MD5(CONCAT('order-', d0.d, d1.d, d2.d, d3.d, d4.d)),
       1000 + FLOOR(RAND() * 10000),
       1 + FLOOR(RAND() * 100),
       IF(RAND() < 0.8, 0, 1),
       ROUND(10 + RAND() * 990, 2),
       '2026-01-01 00:00:00'
         + INTERVAL FLOOR(RAND() * 300) DAY
         + INTERVAL FLOOR(RAND() * 86400) SECOND
FROM digit d0, digit d1, digit d2, digit d3, digit d4;

-- ---------- 2. 用户表：1 万行 ----------
-- phone 故意用 varchar 存（练习 5 隐式类型转换要用），索引提前建好
DROP TABLE IF EXISTS users;
CREATE TABLE users (
  id    INT AUTO_INCREMENT PRIMARY KEY,
  name  VARCHAR(50) NOT NULL,
  phone VARCHAR(20) NOT NULL,
  KEY idx_phone (phone)
) COMMENT '用户表，1万行';

INSERT INTO users (name, phone)
SELECT CONCAT('用户', n.n),
       CONCAT('138', LPAD(n.n, 8, '0'))
FROM (SELECT d0.d + d1.d*10 + d2.d*100 + d3.d*1000 + d4.d*10000 AS n
      FROM digit d0, digit d1, digit d2, digit d3, digit d4
      LIMIT 10000) n;

-- ---------- 3. 商品表：100 行小表（练习 7 JOIN 要用）----------
DROP TABLE IF EXISTS products;
CREATE TABLE products (
  id    INT AUTO_INCREMENT PRIMARY KEY,
  name  VARCHAR(50) NOT NULL,
  price DECIMAL(10,2) NOT NULL
) COMMENT '商品表，100行';

INSERT INTO products (name, price)
SELECT CONCAT('商品', n.n), ROUND(10 + RAND() * 990, 2)
FROM (SELECT d0.d + d1.d*10 AS n FROM digit d0, digit d1) n;

-- ---------- 4. 验证一下造了多少 ----------
SELECT 'orders'   AS tbl, COUNT(*) AS rows_cnt FROM orders
UNION ALL SELECT 'users',    COUNT(*) FROM users
UNION ALL SELECT 'products', COUNT(*) FROM products;
