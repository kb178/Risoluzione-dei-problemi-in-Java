package com.example.seckill.service;

import com.google.common.util.concurrent.RateLimiter;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 限流服务
 * 使用Guava RateLimiter实现令牌桶算法
 */
@Service
public class RateLimitService {

    /**
     * 存储每个接口的限流器
     */
    private final Map<String, RateLimiter> rateLimiterMap = new ConcurrentHashMap<>();

    /**
     * 默认QPS（每秒请求数）
     */
    private static final double DEFAULT_QPS = 100;

    /**
     * 尝试获取令牌（带等待时间）
     * 解决初始时桶为空的问题
     * 
     * @param key 接口标识
     * @param qps 每秒请求数
     * @return true:获取成功，false:获取失败（被限流）
     */
    public boolean tryAcquire(String key, double qps) {
        RateLimiter rateLimiter = getRateLimiter(key, qps);
        // 等待最多100ms，让令牌生成
        return rateLimiter.tryAcquire(100, TimeUnit.MILLISECONDS);
    }

    /**
     * 尝试获取令牌（不等待）
     * 
     * @param key 接口标识
     * @return true:获取成功，false:获取失败（被限流）
     */
    public boolean tryAcquire(String key) {
        return tryAcquire(key, DEFAULT_QPS);
    }

    /**
     * 尝试获取令牌（等待指定时间）
     * 
     * @param key 接口标识
     * @param timeoutMs 等待时间（毫秒）
     * @return true:获取成功，false:获取失败（超时）
     */
    public boolean tryAcquireWithTimeout(String key, long timeoutMs) {
        RateLimiter rateLimiter = getRateLimiter(key, DEFAULT_QPS);
        return rateLimiter.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 获取限流器（如果不存在则创建）
     */
    private RateLimiter getRateLimiter(String key, double qps) {
        return rateLimiterMap.computeIfAbsent(key, k -> {
            RateLimiter limiter = RateLimiter.create(qps);
            // 预热：立即生成一些令牌
            limiter.tryAcquire();  // 消耗一个令牌，触发生成
            return limiter;
        });
    }

    /**
     * 获取当前QPS配置
     */
    public double getRate(String key) {
        RateLimiter rateLimiter = rateLimiterMap.get(key);
        return rateLimiter != null ? rateLimiter.getRate() : DEFAULT_QPS;
    }

    /**
     * 动态调整QPS
     */
    public void setRate(String key, double newQps) {
        RateLimiter rateLimiter = getRateLimiter(key, newQps);
        rateLimiter.setRate(newQps);
    }
}
