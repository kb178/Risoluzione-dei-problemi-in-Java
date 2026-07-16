package com.example.seckill.annotation;

import java.lang.annotation.*;

/**
 * 限流注解
 * 标注在方法上，实现声明式限流
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {
    
    /**
     * 接口标识（默认使用方法名）
     */
    String value() default "";
    
    /**
     * 每秒请求数（QPS）
     */
    double qps() default 100;
    
    /**
     * 等待时间（毫秒），0表示不等待
     */
    long timeoutMs() default 0;
    
    /**
     * 限流提示信息
     */
    String message() default "系统繁忙，请稍后重试";
}
