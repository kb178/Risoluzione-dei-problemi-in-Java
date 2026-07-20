package com.example.seckill.controller;

import com.example.seckill.annotation.RateLimit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

import com.example.seckill.service.SeckillService;

import java.util.HashMap;
import java.util.Map;

/**
 * 秒杀接口V2（分布式锁版本）
 * 与V1的区别：使用Redisson分布式锁替代Lua脚本
 *
 * 测试地址：
 * V1（Lua脚本）：GET /seckill/1?userId=1001
 * V2（分布式锁）：GET /seckill/v2/1?userId=1001
 */
@RestController
@RequestMapping("/seckill/v2")
public class SeckillControllerV2 {

    private final SeckillService seckillService;

    /**
     * 注入V2版本的Service
     * @Qualifier指定Bean名称为"seckillServiceV2"
     */
    public SeckillControllerV2(@Qualifier("seckillServiceV2") SeckillService seckillService) {
        this.seckillService = seckillService;
    }

    /**
     * 秒杀接口V2（分布式锁版本，QPS限制100）
     */
    @GetMapping("/{productId}")
    @RateLimit(qps = 100, message = "请求过于频繁，请稍后重试")
    public Map<String, Object> seckill(@PathVariable Long productId,
                                       @RequestParam(defaultValue = "1") Long userId) {
        Map<String, Object> result = new HashMap<>();
        try {
            String message = seckillService.seckill(productId, userId);
            result.put("code", 200);
            result.put("message", message);
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", e.getMessage());
        }
        return result;
    }
}
