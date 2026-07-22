-- 创建数据库
CREATE DATABASE IF NOT EXISTS short_url DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE short_url;

-- 短链接表
CREATE TABLE IF NOT EXISTS short_url (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    short_code VARCHAR(10) NOT NULL COMMENT '短码',
    original_url VARCHAR(2048) NOT NULL COMMENT '原始URL',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    click_count BIGINT DEFAULT 0 COMMENT '点击次数',
    deleted TINYINT DEFAULT 0 COMMENT '是否删除 0-未删除 1-已删除',
    UNIQUE KEY uk_short_code (short_code),
    INDEX idx_original_url (original_url(100))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='短链接表';
