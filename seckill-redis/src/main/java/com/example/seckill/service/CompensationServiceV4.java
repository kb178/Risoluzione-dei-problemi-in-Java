package com.example.seckill.service;

import com.example.seckill.entity.Order;

/**
 * 补偿方案4：消息队列补偿
 * 核心思想：使用MQ异步处理订单，支持重试和死信队列
 * 适用于：高并发、异步处理、需要削峰填谷的场景
 */
public interface CompensationServiceV4 {
    
    /**
     * 带补偿的秒杀方法
     */
    Order seckillWithCompensation(Long productId, Long userId);
}
