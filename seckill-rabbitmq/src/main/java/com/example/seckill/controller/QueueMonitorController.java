package com.example.seckill.controller;

import com.example.seckill.service.QueueMonitorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 队列监控Controller
 * 提供队列状态查看接口
 */
@RestController
@RequestMapping("/monitor")
public class QueueMonitorController {

    @Autowired
    private QueueMonitorService queueMonitorService;

    /**
     * 获取队列状态
     * GET /monitor/status
     */
    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> status = queueMonitorService.getStatus();
            result.put("code", 200);
            result.put("message", "获取成功");
            result.put("data", status);
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", e.getMessage());
        }
        return result;
    }
}
