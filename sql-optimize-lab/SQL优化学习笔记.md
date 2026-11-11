# SQL 优化学习笔记

SQL 优化的版图就一条链：

定位 → 索引 → 写法 → 表结构 → 架构

加索引在第二环，而很多人没进过第一环。不看执行计划就动手，加完没效果，卡住。这份笔记按这条链记，动手材料在同目录：

| 文件 | 干什么 |
|------|--------|
| `lab.sql` | 建 sql_lab 库、3 张表、造数据，跑一次 |
| `exercises.sql` | 11 个练习，一段一段贴进客户端 |

```bash
mysql -h127.0.0.1 -uroot -p --default-character-set=utf8mb4 < lab.sql
mysql -h127.0.0.1 -uroot -p --default-character-set=utf8mb4   # 再进来贴练习
```

连本机要带 `-h127.0.0.1`。这台机器上有两套 MySQL，裸连走默认管道会连到另一套上去。

## EXPLAIN

拿到 SQL 先别猜，让 MySQL 说它打算怎么干：

```sql
EXPLAIN SELECT * FROM orders WHERE user_id = 2000;
```

字段一大堆，要看的就三个：

| 字段 | 看什么 | 红灯 |
|------|--------|------|
| type | 访问方式 | ALL（全表扫描） |
| rows | 预估扫描行数 | 几万几十万 |
| Extra | 额外动作 | Using filesort、Using temporary |

type 从快到慢：const → eq_ref → ref → range → index → ALL。注意 index 不是好词，它照样把整棵索引从头走到尾，跟 ALL 是难兄难弟，只是少读点数据。要的是 ref 和 range。

Extra 里出现 Using index 是好事，说明走了覆盖索引（下面讲）。出现 filesort 或 temporary，就是 MySQL 在替你多干活。

优化的动作就一个：type 往左推，rows 从几万打到几行。

## 索引

加索引谁都会，坑在细节。

**最左前缀。** 联合索引 (a, b, c) 不是只认最左列，是从最左开始连续的一段都算数：

| WHERE | 用到 |
|-------|------|
| a | a |
| a AND b | a, b |
| a AND b AND c | 全部 |
| b / c / b AND c | 没从 a 开始，用不上 |
| a AND c | 只用 a，断在 b |

短可以，断不行。还有个反直觉的：WHERE 里条件的书写顺序无所谓，(user_id, product_id) 碰上 `WHERE product_id=88 AND user_id=2000` 照样全走，优化器自己会调换。

**覆盖索引。** 要查的列全在某棵索引里，就不用回表，直接从索引拿完，Extra 显示 Using index。它不是一种专门建的索引，是查询和索引之间的一种关系。二级索引里还藏着主键，所以 `SELECT id, user_id, product_id` 也算覆盖。SELECT * 的罪状就在这：星号里总有索引装不下的列，一回表，覆盖就破了。

**失效的坑。** 面试和线上都高频：

```sql
WHERE DATE(create_time) = '2026-06-15'   -- 列上套函数，废
WHERE create_time >= '2026-06-15'
  AND create_time < '2026-06-16'         -- 改写成范围，活

WHERE phone = 13800000123   -- phone 是 varchar，隐式转数字，废
WHERE phone = '13800000123' -- 带引号，活

WHERE name LIKE '%张'       -- 左模糊，B+ 树没法跳，废
WHERE name LIKE '张%'       -- 活
```

规律就一句：索引列要裸着用。套函数、隐式转换、左模糊、跳前导列，中一个废一个条件。注意废的是那个条件，不是整条查询，最左列还占着的话前面照常走（练习 8 有现场）。

**索引要花钱。** 每多一棵，写入多维护一棵 B+ 树，还占磁盘。每个字段都建，等于写入变慢。一张表三五个封顶。建给谁，看等值查询的频次乘区分度（练习 11）。

## SQL 写法

别 SELECT *。省 IO 是次要的，保住覆盖索引是主要的。

深分页是隐形杀手。`LIMIT 100000, 10` 的意思是扫 100010 行，扔掉前面 10 万行。改成游标 `WHERE id > 上一页最后一条的 id`，或者延迟关联：子查询先取 id 再回表。练习 6 有耗时对比。

循环里一条条 INSERT/UPDATE，N 条就是 N 次网络往返。拼成一条多 VALUES，或者 JDBC 打开 rewriteBatchedStatements。秒杀那篇里 2000 线程 4000 次库操作，就是这个病的极端版。

UNION 会去重（临时表加排序），确定没重复就用 UNION ALL。JOIN 记得小表驱动大表，被驱动表的关联列要有索引（练习 7）。

## 表结构

类型够用就好，status 用 tinyint，别上 int。主键用自增整数：UUID 字符串当主键是灾难，插入全是随机写，B+ 树页分裂一片。正确姿势是自增 id 当主键，业务单号单独做唯一索引，orders 表就是这么设计的，跟 seckill 的 order_info 一个路子。字段尽量 NOT NULL 加默认值。text 这类大字段拆去子表，别拖累热查询。

## 架构

单条 SQL 优化到头也就几十毫秒。量真上来是另一层活：缓存挡读写、MQ 削峰、读写分离、分库分表。

秒杀那篇其实也算 SQL 优化：一条原子的 `UPDATE ... AND stock > 0`，把应用层的"先查再扣"干掉，无效往返砍半，并发窗口也没了。上 Redis 和 MQ 是同一个思路再放大一层。减少无效往返，这句话在哪个层面都成立。

## 动手路线

| 练习 | 练什么 | 看什么 |
|------|--------|--------|
| 1 | 全表扫描变索引 | type/rows 前后变化 |
| 2 | 最左前缀 | 三条 WHERE 谁能走 |
| 3 | 覆盖索引 | SELECT * 和具体列的 Extra |
| 4 | 函数杀索引 | 谁全表谁 range |
| 5 | 隐式转换 | 带不带引号差在哪 |
| 6 | 深分页 | LIMIT 偏移 vs 游标 |
| 7 | JOIN | 被驱动表没索引时 type 是什么 |
| 8 | ORDER BY | filesort 什么时候消失 |
| 9 | COUNT | 三个数一样吗 |
| 10 | 慢查询日志 | 谁被记进账本 |
| 11 | 区分度 | 建了索引一定快吗 |

跑之前先写自己的预测再看结果。猜错的地方就是盲区。

## 补：高频的四个

主干之外这四个出现频率最高，练习 8 到 11 对应。

**ORDER BY 和 filesort。** 引擎交出来的行是访问路径的顺序，不是你要的顺序。两边对不上，MySQL 就自己排一遍，Extra 记一笔 Using filesort。这名字有历史包袱，不一定写磁盘，sort_buffer 够大就在内存里排。它的成本按参与排序的行数算，跟返回几行无关，LIMIT 10 也得先把十万行排完才知道是哪 10 条。消掉它就是把顺序做进索引：ORDER BY create_time 有索引就顺着读；WHERE 等值加 ORDER BY 就拿联合索引 (user_id, create_time) 把两段接上，等值钉住前缀，后面的列天然有序。混方向排序（a ASC, b DESC）在 MySQL 8 可以建 DESC 索引对付。

**COUNT。** count(*) 和 count(1) 一样，InnoDB 数行数不挑列，挑当时最小的那棵索引。count(col) 不统计 NULL，col 不在索引里还得整行读。大表随手一个 count(*) 就是慢查询，能缓存能预计算就别实时数。

**慢查询日志。** 前面都是拿一条 SQL 分析它，实战第一步是从一万条里找出该优化谁。slow_query_log 加 long_query_time，超过阈值自动落盘，演示时 log_output='TABLE' 可以直接记进 mysql.slow_log 表里查。坑在 long_query_time 有 global 和 session 两个值，SET GLOBAL 之后当前窗口还是老值，要补一句 SET SESSION。汇总拿 mysqldumpslow 或 pt-query-digest 嚼日志文件，嫌麻烦就 sys.statement_analysis 看指纹排行。

**区分度。** COUNT(DISTINCT col) 除以 COUNT(*)，越接近 1 越值得建。status 这种两三个值的列，建了索引查大范围也不快，甚至更慢：5 万次随机查找 vs 10 万行顺序扫描。练习 11 里有计时对比，跑完就服了。

## 还没覆盖的

面试爱问的第二梯队：聚簇索引和回表的机制（现在只有结论没有过程）、JOIN 的 NLJ/BNL、小表驱动大表的底层原因、EXISTS vs IN、OR 改 UNION ALL、子查询改 JOIN、MySQL 8 的函数索引和生成列。

深水区第三梯队，用到再开：统计信息和优化器选错索引、buffer pool、redo/undo 和锁模型、分区表、反范式和汇总表。

水平自评：日常开发够用，面试这道题基本满配，DBA 向还差第三梯队。
