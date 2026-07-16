package com.example.seckill.controller;

import com.example.seckill.entity.Order;
import com.example.seckill.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 补偿方案测试Controller
 */
@RestController
@RequestMapping("/compensation")
public class CompensationController {

    @Autowired
    private CompensationServiceV1 compensationServiceV1;

    @Autowired
    private CompensationServiceV2 compensationServiceV2;

    @Autowired
    private CompensationServiceV3 compensationServiceV3;

    @Autowired
    private CompensationServiceV4 compensationServiceV4;

    /**
     * 方案1：事务补偿
     * GET /compensation/v1/1?userId=1001
     */
    @GetMapping("/v1/{productId}")
    public Map<String, Object> seckillV1(@PathVariable Long productId,
                                         @RequestParam(defaultValue = "1") Long userId) {
        Map<String, Object> result = new HashMap<>();
        try {
            Order order = compensationServiceV1.seckillWithCompensation(productId, userId);
            result.put("code", 200);
            result.put("message", "秒杀成功（方案1：事务补偿）");
            result.put("data", order);
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 方案2：定时任务补偿
     * GET /compensation/v2/1?userId=1001
     */
    @GetMapping("/v2/{productId}")
    public Map<String, Object> seckillV2(@PathVariable Long productId,
                                         @RequestParam(defaultValue = "1") Long userId) {
        Map<String, Object> result = new HashMap<>();
        try {
            Order order = compensationServiceV2.seckillWithCompensation(productId, userId);
            result.put("code", 200);
            result.put("message", "秒杀成功（方案2：定时任务补偿）");
            result.put("data", order);
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 方案3：状态机补偿
     * GET /compensation/v3/1?userId=1001
     */
    @GetMapping("/v3/{productId}")
    public Map<String, Object> seckillV3(@PathVariable Long productId,
                                         @RequestParam(defaultValue = "1") Long userId) {
        Map<String, Object> result = new HashMap<>();
        try {
            Order order = compensationServiceV3.seckillWithCompensation(productId, userId);
            result.put("code", 200);
            result.put("message", "秒杀成功（方案3：状态机补偿）");
            result.put("data", order);
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 方案4：消息队列补偿
     * GET /compensation/v4/1?userId=1001
     */
    @GetMapping("/v4/{productId}")
    public Map<String, Object> seckillV4(@PathVariable Long productId,
                                         @RequestParam(defaultValue = "1") Long userId) {
        Map<String, Object> result = new HashMap<>();
        try {
            Order order = compensationServiceV4.seckillWithCompensation(productId, userId);
            result.put("code", 200);
            result.put("message", "秒杀成功（方案4：消息队列补偿）");
            result.put("data", order);
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", e.getMessage());
        }
        return result;
    }
}
