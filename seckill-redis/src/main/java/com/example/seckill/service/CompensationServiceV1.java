package com.example.seckill.service;

import com.example.seckill.entity.Order;

/**
 * 补偿方案1：事务补偿
 * 核心思想：Redis扣库存和数据库操作放在同一个"逻辑事务"中
 * 如果数据库操作失败，立即回滚Redis
 */
public interface CompensationServiceV1 {
    
    /**
     * 带补偿的秒杀方法
     * @param productId 商品ID
     * @param userId 用户ID
     * @return 订单信息
     */
    Order seckillWithCompensation(Long productId, Long userId);
}
