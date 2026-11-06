-- ============================================================
-- SQL 优化练习（对着 SQL优化学习笔记.md 用）
-- 用法：mysql -h127.0.0.1 -uroot -p --default-character-set=utf8mb4
--       然后一段一段贴进来跑，每段先自己猜结果再看 EXPLAIN
-- 如果重复跑某个练习报 "Duplicate key name"，先把那条 CREATE INDEX 换成对应的 DROP INDEX
-- ============================================================

USE sql_lab;

-- ============ 练习 1：全表扫描 → 索引 ============
-- 先猜：这条查询会扫多少行？
EXPLAIN SELECT * FROM orders WHERE user_id = 2000;

CREATE INDEX idx_user ON orders(user_id);

-- 再看：type 从 ALL 变成 ref，rows 从 10万 掉到 ~10
EXPLAIN SELECT * FROM orders WHERE user_id = 2000;

-- ============ 练习 2：联合索引最左前缀 ============
-- 清场，换成联合索引
DROP INDEX idx_user ON orders;
CREATE INDEX idx_user_prod ON orders(user_id, product_id);

-- 三条 SQL 先猜哪条能走索引，再看 EXPLAIN 的 type/rows：
EXPLAIN SELECT * FROM orders WHERE user_id = 2000;
EXPLAIN SELECT * FROM orders WHERE product_id = 88;
EXPLAIN SELECT * FROM orders WHERE user_id = 2000 AND product_id = 88;

-- ============ 练习 3：覆盖索引，别 SELECT * ============
-- 盯 Extra 字段：一条显示 Using index（索引里拿完了），一条要回表
EXPLAIN SELECT user_id, product_id FROM orders WHERE user_id = 2000;
EXPLAIN SELECT *                  FROM orders WHERE user_id = 2000;

-- ============ 练习 4：函数让索引失效 ============
CREATE INDEX idx_ctime ON orders(create_time);

-- 先猜再跑：哪条是 ALL，哪条是 range？
EXPLAIN SELECT * FROM orders WHERE DATE(create_time) = '2026-06-15';
EXPLAIN SELECT * FROM orders
WHERE create_time >= '2026-06-15' AND create_time < '2026-06-16';

-- ============ 练习 5：隐式类型转换 ============
-- users.phone 是 varchar，索引建好了。对比带引号 / 不带引号
EXPLAIN SELECT * FROM users WHERE phone = '13800000123';
EXPLAIN SELECT * FROM users WHERE phone = 13800000123;

-- ============ 练习 6：深分页 ============
-- 先看 EXPLAIN 的 rows：10万+ vs 10
EXPLAIN SELECT * FROM orders ORDER BY id LIMIT 100000, 10;
EXPLAIN SELECT * FROM orders WHERE id > 100000 ORDER BY id LIMIT 10;

-- 再感受耗时（各跑一次看 ms）
SET @t0 = NOW(6);
SELECT * FROM orders ORDER BY id LIMIT 100000, 10;
SELECT ROUND(TIMESTAMPDIFF(MICROSECOND, @t0, NOW(6))/1000, 1) AS ms_深分页;

SET @t0 = NOW(6);
SELECT * FROM orders WHERE id > 100000 ORDER BY id LIMIT 10;
SELECT ROUND(TIMESTAMPDIFF(MICROSECOND, @t0, NOW(6))/1000, 1) AS ms_游标;

-- ============ 练习 7：JOIN 被驱动表要有索引 ============
-- products(100) 关联 orders(10万)。先看 orders.product_id 没索引时的 EXPLAIN
EXPLAIN SELECT p.name, COUNT(*) AS cnt
FROM products p JOIN orders o ON o.product_id = p.id
GROUP BY p.id;

CREATE INDEX idx_prod ON orders(product_id);

-- 再跑一遍，看 EXPLAIN 里 orders 那行的 type 变没变
EXPLAIN SELECT p.name, COUNT(*) AS cnt
FROM products p JOIN orders o ON o.product_id = p.id
GROUP BY p.id;

-- ============ 加餐：EXPLAIN 之后想看 MySQL 实际怎么改写你的 SQL ============
-- 有些失效是隐性的（比如练习 5），跑完 EXPLAIN 接着跑这个，能看到改写后的真身
SHOW WARNINGS;
