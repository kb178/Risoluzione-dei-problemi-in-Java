package com.example.seckill.controller;
import com.example.seckill.entity.Order;
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
     * 方案1：原始版本 - 有超卖漏洞
     * 测试：GET /seckill/v1/1?userId=1001
     */
    @GetMapping("/v1/{productId}")
    public Map<String, Object> seckillV1(@PathVariable Long productId,
                                         @RequestParam(defaultValue = "1") Long userId) {
        Map<String, Object> result = new HashMap<>();
        try {
            Order order = seckillService.seckillV1(productId, userId);
            result.put("code", 200);
            result.put("message", "秒杀成功");
            result.put("data", order);
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 方案2：@Transactional版本 - 仍然超卖
     * 测试：GET /seckill/v2/1?userId=1001
     */
    @GetMapping("/v2/{productId}")
    public Map<String, Object> seckillV2(@PathVariable Long productId,
                                         @RequestParam(defaultValue = "1") Long userId) {
        Map<String, Object> result = new HashMap<>();
        try {
            Order order = seckillService.seckillV2(productId, userId);
            result.put("code", 200);
            result.put("message", "秒杀成功");
            result.put("data", order);
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 方案3：悲观锁版本 - 解决超卖，但性能差
     * 测试：GET /seckill/v3/1?userId=1001
     */
    @GetMapping("/v3/{productId}")
    public Map<String, Object> seckillV3(@PathVariable Long productId,
                                         @RequestParam(defaultValue = "1") Long userId) {
        Map<String, Object> result = new HashMap<>();
        try {
            Order order = seckillService.seckillV3悲观锁(productId, userId);
            result.put("code", 200);
            result.put("message", "秒杀成功");
            result.put("data", order);
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 方案4：乐观锁版本 - 解决超卖，性能较好
     * 测试：GET /seckill/v4/1?userId=1001
     */
    @GetMapping("/v4/{productId}")
    public Map<String, Object> seckillV4(@PathVariable Long productId,
                                         @RequestParam(defaultValue = "1") Long userId) {
        Map<String, Object> result = new HashMap<>();
        try {
            Order order = seckillService.seckillV4乐观锁(productId, userId);
            result.put("code", 200);
            result.put("message", "秒杀成功");
            result.put("data", order);
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 兼容旧接口 - 默认使用方案1
     */
    @GetMapping("/{productId}")
    public Map<String, Object> seckill(@PathVariable Long productId,
                                       @RequestParam(defaultValue = "1") Long userId) {
        return seckillV1(productId, userId);
    }
}