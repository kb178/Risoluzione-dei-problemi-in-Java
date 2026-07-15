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
    @GetMapping("/{productId}")
    public Map<String, Object> seckill(@PathVariable Long productId,
                                       @RequestParam(defaultValue = "1") Long userId) {
        Map<String, Object> result = new HashMap<>();
        try {
            Order order = seckillService.seckill(productId, userId);
            result.put("code", 200);
            result.put("message", "秒杀成功");
            result.put("data", order);
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", e.getMessage());
        }
        return result;
    }
}