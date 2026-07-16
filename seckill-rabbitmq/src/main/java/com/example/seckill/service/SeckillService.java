package com.example.seckill.service;

public interface SeckillService {
    /**
     * 秒杀接口（异步版本）
     * @param productId 商品ID
     * @param userId 用户ID
     * @return 排队结果
     */
    String seckill(Long productId, Long userId);
}
