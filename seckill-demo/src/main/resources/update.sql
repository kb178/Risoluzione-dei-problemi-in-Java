-- 更新现有表结构，添加乐观锁版本号字段
-- 如果你已经创建了product表，运行这个脚本来添加version字段

USE seckill;

-- 添加version字段
ALTER TABLE product ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号' AFTER price;

-- 更新现有数据的version为0
UPDATE product SET version = 0 WHERE version IS NULL;

-- 查看表结构
DESCRIBE product;

-- 查看数据
SELECT * FROM product;
