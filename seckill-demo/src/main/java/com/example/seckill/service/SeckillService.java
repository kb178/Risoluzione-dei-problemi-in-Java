package com.example.seckill.service;

import com.example.seckill.entity.Order;

public interface SeckillService {
    Order seckill(Long productId, Long userId);
}
