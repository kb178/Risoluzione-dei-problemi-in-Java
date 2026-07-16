package com.example.seckill.service;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 队列监控服务
 * 监控队列深度，触发自动扩容
 */
@Service
public class QueueMonitorService {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // ==================== 配置参数 ====================
    // 队列名称
    private static final String ORDER_QUEUE = "seckill.order.queue";
    
    // 告警阈值：队列深度超过此值触发告警
    private static final int ALERT_THRESHOLD = 1000;
    
    // 扩容阈值：队列深度超过此值触发扩容
    private static final int SCALE_UP_THRESHOLD = 5000;
    
    // 缩容阈值：队列深度低于此值触发缩容
    private static final int SCALE_DOWN_THRESHOLD = 100;
    
    // 最大消费者数量
    private static final int MAX_CONSUMERS = 20;
    
    // 最小消费者数量
    private static final int MIN_CONSUMERS = 2;
    
    // 当前消费者数量
    private int currentConsumerCount = 2;
    
    // 消费者线程池
    private ThreadPoolExecutor consumerThreadPool;
    
    // Redis key
    private static final String CONSUMER_COUNT_KEY = "queue:consumer:count";
    private static final String QUEUE_DEPTH_KEY = "queue:depth";
    private static final String ALERT_KEY = "queue:alert";

    /**
     * 初始化线程池
     */
    @jakarta.annotation.PostConstruct
    public void init() {
        consumerThreadPool = new ThreadPoolExecutor(
            MIN_CONSUMERS,           // 核心线程数
            MAX_CONSUMERS,           // 最大线程数
            60L, TimeUnit.SECONDS,   // 空闲线程存活时间
            new LinkedBlockingQueue<>(1000),  // 任务队列
            new ThreadPoolExecutor.CallerRunsPolicy()  // 拒绝策略
        );
        
        // 从Redis恢复消费者数量
        String countStr = stringRedisTemplate.opsForValue().get(CONSUMER_COUNT_KEY);
        if (countStr != null) {
            currentConsumerCount = Integer.parseInt(countStr);
        }
        
        System.out.printf("队列监控服务初始化完成，当前消费者数量：%d%n", currentConsumerCount);
    }

    /**
     * 定时任务：每10秒检查一次队列深度
     */
    @Scheduled(fixedRate = 10000)
    public void monitorQueueDepth() {
        try {
            // 1. 获取队列深度
            int queueDepth = getQueueDepth();
            
            // 2. 保存到Redis（供其他服务查看）
            stringRedisTemplate.opsForValue().set(QUEUE_DEPTH_KEY, String.valueOf(queueDepth));
            
            // 3. 打印日志
            System.out.printf("队列监控：深度=%d, 消费者=%d%n", queueDepth, currentConsumerCount);
            
            // 4. 判断是否需要扩容/缩容
            handleScaling(queueDepth);
            
            // 5. 判断是否需要告警
            handleAlert(queueDepth);
            
        } catch (Exception e) {
            System.out.println("队列监控异常：" + e.getMessage());
        }
    }

    /**
     * 获取队列深度
     * 使用RabbitMQ Management API
     */
    private int getQueueDepth() {
        try {
            // 方法1：使用RabbitMQ HTTP API
            // 需要添加依赖：spring-boot-starter-actuator
            // 或者使用RestTemplate调用API
            
            // 方法2：从Redis模拟（实际项目中用API）
            // 这里简化处理，从Redis获取
            String depthStr = stringRedisTemplate.opsForValue().get("queue:depth:current");
            if (depthStr != null) {
                return Integer.parseInt(depthStr);
            }
            
            // 方法3：直接调用RabbitMQ命令行工具
            // Runtime.getRuntime().exec("rabbitmqctl list_queues name messages");
            
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 处理扩容/缩容逻辑
     */
    private void handleScaling(int queueDepth) {
        if (queueDepth > SCALE_UP_THRESHOLD && currentConsumerCount < MAX_CONSUMERS) {
            // 需要扩容
            scaleUp(queueDepth);
        } else if (queueDepth < SCALE_DOWN_THRESHOLD && currentConsumerCount > MIN_CONSUMERS) {
            // 需要缩容
            scaleDown(queueDepth);
        }
    }

    /**
     * 扩容消费者
     */
    private void scaleUp(int queueDepth) {
        int targetCount = Math.min(currentConsumerCount + 2, MAX_CONSUMERS);
        
        System.out.printf("触发扩容：队列深度=%d, 当前消费者=%d, 目标消费者=%d%n", 
            queueDepth, currentConsumerCount, targetCount);
        
        // 启动新的消费者
        for (int i = currentConsumerCount; i < targetCount; i++) {
            startNewConsumer();
        }
        
        currentConsumerCount = targetCount;
        saveConsumerCount();
        
        System.out.printf("扩容完成：当前消费者数量=%d%n", currentConsumerCount);
    }

    /**
     * 缩容消费者
     */
    private void scaleDown(int queueDepth) {
        int targetCount = Math.max(currentConsumerCount - 2, MIN_CONSUMERS);
        
        System.out.printf("触发缩容：队列深度=%d, 当前消费者=%d, 目标消费者=%d%n", 
            queueDepth, currentConsumerCount, targetCount);
        
        // 停止多余的消费者（这里简化处理，实际需要优雅停止）
        currentConsumerCount = targetCount;
        saveConsumerCount();
        
        System.out.printf("缩容完成：当前消费者数量=%d%n", currentConsumerCount);
    }

    /**
     * 启动新消费者
     */
    private void startNewConsumer() {
        // 实际项目中，这里会启动新的消费者实例
        // 可以通过以下方式：
        // 1. 创建新的线程
        // 2. 调用Kubernetes API创建新Pod
        // 3. 发送消息到消费者集群
        
        System.out.println("启动新消费者实例");
        
        // 模拟启动新消费者
        if (consumerThreadPool != null) {
            consumerThreadPool.submit(() -> {
                System.out.println("新消费者已启动，开始消费消息");
                // 这里可以调用消费者的逻辑
            });
        }
    }

    /**
     * 处理告警
     */
    private void handleAlert(int queueDepth) {
        if (queueDepth > ALERT_THRESHOLD) {
            // 触发告警
            String alertKey = ALERT_KEY + ":" + System.currentTimeMillis();
            stringRedisTemplate.opsForValue().set(alertKey, 
                String.format("队列积压告警：深度=%d, 消费者=%d", queueDepth, currentConsumerCount));
            
            System.out.printf("⚠️ 告警：队列积压严重！深度=%d, 消费者=%d%n", queueDepth, currentConsumerCount);
            
            // 实际项目中，这里会发送告警通知
            // sendAlert("队列积压告警", queueDepth);
        }
    }

    /**
     * 保存消费者数量到Redis
     */
    private void saveConsumerCount() {
        stringRedisTemplate.opsForValue().set(CONSUMER_COUNT_KEY, String.valueOf(currentConsumerCount));
    }

    /**
     * 获取当前状态（供API调用）
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("queueDepth", getQueueDepth());
        status.put("consumerCount", currentConsumerCount);
        status.put("alertThreshold", ALERT_THRESHOLD);
        status.put("scaleUpThreshold", SCALE_UP_THRESHOLD);
        status.put("scaleDownThreshold", SCALE_DOWN_THRESHOLD);
        return status;
    }
}
