package com.example.seckill.aspect;

import com.example.seckill.annotation.RateLimit;
import com.example.seckill.service.RateLimitService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 限流切面
 * 处理@RateLimit注解
 */
@Aspect
@Component
public class RateLimitAspect {

    @Autowired
    private RateLimitService rateLimitService;

    @Around("@annotation(com.example.seckill.annotation.RateLimit)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        // 1. 获取注解信息
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        RateLimit rateLimit = method.getAnnotation(RateLimit.class);
        
        // 2. 获取接口标识
        String key = rateLimit.value();
        if (key.isEmpty()) {
            key = method.getDeclaringClass().getSimpleName() + "." + method.getName();
        }
        
        // 3. 尝试获取令牌
        boolean acquired;
        if (rateLimit.timeoutMs() > 0) {
            // 等待指定时间
            acquired = rateLimitService.tryAcquireWithTimeout(key, rateLimit.timeoutMs());
        } else {
            // 使用默认等待时间（100ms）
            acquired = rateLimitService.tryAcquire(key, rateLimit.qps());
        }
        
        // 4. 判断是否被限流
        if (!acquired) {
            throw new IllegalStateException(rateLimit.message());
        }
        
        // 5. 放行
        return joinPoint.proceed();
    }
}
