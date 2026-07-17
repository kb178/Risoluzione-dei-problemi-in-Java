package com.example.seckill.service;

import com.example.seckill.entity.Order;

/**
 * 补偿方案3：状态机补偿
 * 核心思想：使用订单状态跟踪处理进度，支持状态流转和重试
 * 适用于：复杂业务流程、需要人工干预的场景
 */
public interface CompensationServiceV3 {
    
    /**
     * 带补偿的秒杀方法
     */
    Order seckillWithCompensation(Long productId, Long userId);
    
    /**
     * 重试失败的订单
     */
    void retryFailedOrders();
}
