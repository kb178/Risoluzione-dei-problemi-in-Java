package com.example.seckill;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling  // 启用定时任务
@EnableRetry       // 启用重试（修复漏洞16：AUTO模式无限重试）
public class SeckillRabbitmqApplication {

    public static void main(String[] args) {
        SpringApplication.run(SeckillRabbitmqApplication.class, args);
    }
}
