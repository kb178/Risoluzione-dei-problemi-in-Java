package com.example.seckill.service;

import com.example.seckill.entity.Order;

public interface SeckillService {
    /**
     * 方案1：原始版本 - 有超卖漏洞
     */
    Order seckillV1(Long productId, Long userId);

    /**
     * 方案2：@Transactional版本 - 仍然超卖
     */
    Order seckillV2(Long productId, Long userId);

    /**
     * 方案3：悲观锁版本 - 解决超卖，但性能差
     */
    Order seckillV3悲观锁(Long productId, Long userId);

    /**
     * 方案4：乐观锁版本 - 解决超卖，性能较好
     */
    Order seckillV4乐观锁(Long productId, Long userId);
}
