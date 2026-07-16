package com.example.seckill.controller;

import com.example.seckill.annotation.RateLimit;
import com.example.seckill.service.SeckillService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/seckill")
public class SeckillController {

    @Autowired
    private SeckillService seckillService;

    /**
     * 秒杀接口（QPS限制100）
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
