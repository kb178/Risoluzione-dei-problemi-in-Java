package com.example.seckill.service;

import com.example.seckill.entity.Order;

/**
 * 补偿方案2：定时任务补偿
 * 核心思想：定期检查Redis和数据库的不一致数据，进行补偿
 * 适用于：异步操作、MQ失败等场景
 */
public interface CompensationServiceV2 {
    
    /**
     * 带补偿的秒杀方法
     */
    Order seckillWithCompensation(Long productId, Long userId);
    
    /**
     * 定时任务：检查并补偿不一致数据
     */
    void checkAndCompensate();
}
