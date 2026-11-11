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

-- ============ 练习 8：ORDER BY 消掉 filesort ============
-- 索引本身就是有序的：让 ORDER BY 的顺序跟某个索引一致，排序就白送了

-- 场景一：没 WHERE，按时间取最早 10 条
-- （先清掉练习 4 建的 idx_ctime；报 Can't DROP 就说明本来没有，忽略继续）
DROP INDEX idx_ctime ON orders;
-- 先猜：Extra 里会不会有 Using filesort？
EXPLAIN SELECT * FROM orders ORDER BY create_time LIMIT 10;

CREATE INDEX idx_ctime ON orders(create_time);
-- 索引已经是有序的，顺着读就行 → filesort 应该消失了
EXPLAIN SELECT * FROM orders ORDER BY create_time LIMIT 10;

-- 场景二（更常见）：WHERE 等值 + ORDER BY
-- 先猜：会走 user_id 索引吗？filesort 还需要吗？
EXPLAIN SELECT * FROM orders WHERE user_id = 2000 ORDER BY create_time LIMIT 10;

-- 联合索引把"等值"和"排序"两段连起来：(user_id, create_time)
CREATE INDEX idx_user_ctime ON orders(user_id, create_time);
-- user_id 用等值钉住后，create_time 在索引里已经是有序的 → filesort 消失
EXPLAIN SELECT * FROM orders WHERE user_id = 2000 ORDER BY create_time LIMIT 10;

-- ============ 练习 9：COUNT 三兄弟 ============
-- 前置：加个可空列（跑一次就够；报 Duplicate column 就跳过这行）
ALTER TABLE orders ADD COLUMN remark VARCHAR(20) NULL;
UPDATE orders SET remark = '已备注' WHERE id % 2 = 0;

-- 先猜：三个数一样吗？谁最小？
SELECT COUNT(*) AS c_star, COUNT(1) AS c_one, COUNT(remark) AS c_remark FROM orders;

-- 再看 EXPLAIN：
-- count(*)/count(1)：不挑列，挑当时最小的索引直接数（看 key 是谁——这就是知识点）
-- count(remark)：这一列不在任何索引里，只能整行读
EXPLAIN SELECT COUNT(*) FROM orders;
EXPLAIN SELECT COUNT(1) FROM orders;
EXPLAIN SELECT COUNT(remark) FROM orders;

-- 记三句：
-- count(*) ≈ count(1)；count(col) 不统计 NULL；大表上随手 count(*) 本身就是慢查询

-- ============ 练习 10：慢查询日志——先找到该优化谁 ============
-- 之前都是"拿到一条 SQL 分析它"，实战第一步是"从一万条里找出该优化谁"
-- 演示起见：日志记进 mysql.slow_log 表、阈值临时调成 0（全记）
SET GLOBAL log_output = 'TABLE';
SET GLOBAL long_query_time = 0;
SET SESSION long_query_time = 0;  -- 别漏这行：阈值是 global+session 两个值，不然当前窗口还是 10 秒
TRUNCATE TABLE mysql.slow_log;
SET GLOBAL slow_query_log = 1;

-- 跑两个故意慢的（左模糊永远全表扫）
SELECT * FROM orders WHERE order_no LIKE '%abcdef%' LIMIT 5;
SELECT COUNT(*) FROM orders WHERE user_id > 5000;

-- 先关掉再查账本（不关的话，它连你查账本这条 SQL 都记）
SET GLOBAL slow_query_log = 0;
SELECT query_time, LEFT(sql_text, 60) AS sql_preview FROM mysql.slow_log;

-- 还原现场（生产上阈值一般是 1 秒，日志记在文件里，不是表）
SET GLOBAL long_query_time = 10;

-- 真实排查的两个入口：
-- ① 慢日志文件 + mysqldumpslow / pt-query-digest 汇总
-- ② SELECT LEFT(query,50), exec_count FROM sys.statement_analysis
--    ORDER BY exec_count DESC LIMIT 5;   ← 按 SQL 指纹看排行

-- ============ 练习 11：区分度——索引该加给谁 ============
-- 区分度 = COUNT(DISTINCT col) / COUNT(*)，越接近 1 越值得建索引
SELECT COUNT(DISTINCT user_id)    / COUNT(*) AS sel_user,
       COUNT(DISTINCT product_id) / COUNT(*) AS sel_product,
       COUNT(DISTINCT status)     / COUNT(*) AS sel_status
FROM orders;

-- 给区分度最低的 status 建索引，看它救不救得了 80% 的行
CREATE INDEX idx_status ON orders(status);
EXPLAIN SELECT * FROM orders WHERE status = 0;   -- 80% 的行都在这
EXPLAIN SELECT * FROM orders WHERE status = 1;   -- 20%
-- 对比一下同样是 ref 的另一条，看 rows 差多少
EXPLAIN SELECT * FROM orders WHERE user_id = 2000;

-- 计时验证"建了索引也不一定快"（子查询包一层，免得打印 8 万行的时间算进去）
SET @t0 = NOW(6);
SELECT COUNT(*) FROM (SELECT id FROM orders WHERE status = 0) t;
SELECT ROUND(TIMESTAMPDIFF(MICROSECOND, @t0, NOW(6))/1000, 1) AS ms_用索引;

SET @t0 = NOW(6);
SELECT COUNT(*) FROM (SELECT id FROM orders IGNORE INDEX (idx_status) WHERE status = 0) t;
SELECT ROUND(TIMESTAMPDIFF(MICROSECOND, @t0, NOW(6))/1000, 1) AS ms_全表扫;

DROP INDEX idx_status ON orders;

-- 结论：等值查询频次 × 区分度，两个都高才值得建
-- status 这种几个值的列单独立索引省不了事，真查大范围它反而拖后腿

-- ============ 加餐：EXPLAIN 之后想看 MySQL 实际怎么改写你的 SQL ============
-- 有些失效是隐性的（比如练习 5），跑完 EXPLAIN 接着跑这个，能看到改写后的真身
SHOW WARNINGS;
