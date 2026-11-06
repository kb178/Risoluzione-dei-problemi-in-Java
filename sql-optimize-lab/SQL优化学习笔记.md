# SQL 优化学习笔记

加索引只是第二环。整条链是：

**定位 → 索引 → 写法 → 表结构 → 架构**

大部分人跳过第一环直接加索引，加完没效果就懵了。这份笔记是地图，配套动手材料在同目录：

| 文件 | 干什么 |
|------|--------|
| `lab.sql` | 建 `sql_lab` 库 + 3 张表 + 造数据（跑一次） |
| `exercises.sql` | 7 个练习，一段一段贴进 mysql 客户端跑 |

跑法：

```bash
mysql -h127.0.0.1 -uroot -p --default-character-set=utf8mb4 < lab.sql
mysql -h127.0.0.1 -uroot -p --default-character-set=utf8mb4   # 再进来贴练习
```

注意连本机 MySQL 要带 `-h127.0.0.1`（这台机器上有两套 MySQL 实例，裸连默认管道会连错）。

---

## 第 0 环：EXPLAIN（比加索引重要）

别凭感觉优化，先让 MySQL 告诉你它打算怎么执行：

```sql
EXPLAIN SELECT * FROM orders WHERE user_id = 2000;
```

输出里只盯三个字段：

| 字段 | 看什么 | 红灯长什么样 |
|------|--------|--------------|
| **type** | 访问方式 | `ALL` = 全表扫描 |
| **rows** | 预估要扫多少行 | 几万、几十万 |
| **Extra** | 额外动作 | `Using filesort`、`Using temporary` |

type 从快到慢：`const → eq_ref → ref → range → index → ALL`。

**SQL 优化说白了就一件事：把 type 往左推，把 rows 从十万打到个位数。**
`Extra` 里出现 `Using index` 反倒是好事（覆盖索引）。

## 第 1 环：索引的"里子"

### 1. 联合索引有最左前缀

`uk_user_product(user_id, product_id)` 这种联合索引：

```sql
WHERE user_id = 2000                    -- ✅ 走索引（最左列）
WHERE product_id = 88                   -- ❌ 跳过了 user_id，全表扫描
WHERE user_id = 2000 AND product_id = 88 -- ✅ 走索引
```

### 2. 覆盖索引

查询的列全在索引里，就不用"回表"取数据：

```sql
SELECT user_id, product_id FROM orders WHERE user_id = 2000;
-- Extra: Using index，直接从索引拿完
```

这也是别无脑 `SELECT *` 的原因之一——`*` 把回表的路全走一遍。

### 3. 索引失效的几个经典坑

```sql
WHERE DATE(create_time) = '2026-06-15'   -- ❌ 列上套函数，索引废了
WHERE create_time >= '2026-06-15'
  AND create_time < '2026-06-17'         -- ✅ 改写成范围

WHERE phone = 13800000123   -- ❌ phone 是 varchar，隐式转数字，全表
WHERE phone = '13800000123' -- ✅

WHERE name LIKE '%张'       -- ❌ 左模糊，B+树没法跳
WHERE name LIKE '张%'       -- ✅ 还能走
```

一句话规律：**索引列上裸着用（不套函数、不转类型、不左模糊、不跳过前导列），索引才在。**

### 4. 索引不是免费的

每加一个，写入多维护一棵 B+ 树，还占磁盘。给每个字段建索引 = 写入变慢的负优化。一般一张表 3~5 个索引封顶。

## 第 2 环：SQL 写法本身

- **SELECT 具体列**，别 `*`。省 IO，还可能白送覆盖索引。
- **深分页**是隐形杀手：`LIMIT 100000, 10` 的真实含义是"扫 100010 行，扔掉前 10 万行"。改成游标 `WHERE id > 上一页最后一条的id ORDER BY id LIMIT 10`，或者延迟关联（子查询先查 id 再回表）。
- **批量代替循环**：循环里一条条 INSERT/UPDATE = N 次网络往返。一条多 VALUES、或者 `rewriteBatchedStatements=true`。
- **UNION ALL 代替 UNION**：UNION 要去重（临时表+排序），确定没有重复就别付这个钱。
- **JOIN 小表驱动大表**，被驱动表的关联列要有索引。

## 第 3 环：表结构

- 类型够用就好：`status` 用 `tinyint`，别用 `int` 或 `varchar`。
- **主键用自增整数**。拿 UUID 字符串当主键 = 插入全是随机写，B+ 树页分裂一片。正确姿势：自增 `id` 当主键、业务单号 `order_no` 单独做唯一索引（本 lab 的 orders 表就是这么设计的）。
- 字段尽量 `NOT NULL` + 默认值，可空列让索引和统计信息都变复杂。
- 大字段（text、超长 varchar）拆到子表冷热分离，别拖累热查询。

## 第 4 环：架构（终局）

单条 SQL 优化到头也就是几十毫秒的事，量真上来靠：缓存挡读写（Redis）、MQ 削峰、读写分离、分库分表。

一句话串起来：用一条原子的 `UPDATE ... AND stock > 0` 干掉应用层"先查再扣"，是 SQL 优化；把无效请求挡到 Redis/MQ 外面，是架构优化。本质是同一件事——**减少无效往返**。

---

## 动手路线（对应 exercises.sql）

| 练习 | 练什么 | 先猜什么 |
|------|--------|----------|
| 1 | 全表扫描 → 索引 | 加索引前后 type/rows 变化 |
| 2 | 最左前缀 | 三条 WHERE 哪条能走索引 |
| 3 | 覆盖索引 | `SELECT *` 和具体列的 Extra 区别 |
| 4 | 函数杀索引 | `DATE(col)=` 和范围写法谁全表 |
| 5 | 隐式转换 | 带引号和不带引号差在哪 |
| 6 | 深分页 | LIMIT 十万偏移 vs 游标的 rows 和耗时 |
| 7 | JOIN 索引 | 被驱动表没索引时 type 是什么 |

每条 EXPLAIN 跑之前先写一句自己的预测，再看结果——猜错的地方就是你原来理解的盲区。

---

## 进阶地图（这份笔记没覆盖的）

上面五环是实战主干，完整版图还有这些，按优先级排：

**第一梯队（高频，值得补）：**
1. ORDER BY 走索引消除 filesort（WHERE 等值占满索引前缀，ORDER BY 顺索引顺序）
2. COUNT(\*) 一族：count(\*)≈count(1) 走最小二级索引；count(col) 不计 NULL；大表别随手 count
3. 慢查询日志：slow_query_log + long_query_time，或 sys.statement_analysis 按 SQL 指纹汇总——先找到该优化谁
4. 区分度：status 这种低区分度的列不值得建索引

**第二梯队（面试加分）：**
5. 聚簇索引/回表/页分裂的机制（不只结论）
6. JOIN 算法：NLJ vs BNL，小表驱动大表的原因，MySQL 8 hash join
7. 改写手法：EXISTS vs IN、OR 改 UNION ALL、子查询改 JOIN
8. MySQL 8 函数索引 / 虚拟生成列（"函数废索引"的官方解法）

**第三梯队（深水区，到用再学）：**
9. 统计信息 ANALYZE TABLE、优化器选错索引
10. InnoDB：buffer pool、redo/undo、锁模型、大事务
11. 分区表（RANGE/HASH）
12. 反范式/汇总表（预计算，读多写少的大杀器）

水平自评：日常开发 ≈ 够用；Java 面试 SQL 优化题 ≈ 中上（补满第一梯队基本满配）；DBA 向 ≈ 需要第三梯队。
