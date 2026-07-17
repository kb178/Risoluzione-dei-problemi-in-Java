package com.example.seckill.service.impl;

import com.example.seckill.entity.Order;
import com.example.seckill.entity.Product;
import com.example.seckill.mapper.OrderMapper;
import com.example.seckill.mapper.ProductMapper;
import com.example.seckill.service.CompensationServiceV4;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 补偿方案4：消息队列补偿
 * 
 * 核心思想：
 * 1. 秒杀时只扣减Redis库存，发送MQ消息
 * 2. 消费者异步处理订单（写数据库）
 * 3. 失败时重试，超过重试次数进入死信队列
 * 
 * 优点：
 * - 解耦：秒杀和订单处理分离
 * - 削峰：MQ缓冲高并发
 * - 重试：自动重试失败消息
 * - 监控：死信队列便于问题排查
 * 
 * 缺点：
 * - 实现复杂
 * - 需要MQ中间件
 * - 数据最终一致性
 */
@Service
public class CompensationServiceV4Impl implements CompensationServiceV4 {

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final String STOCK_KEY_PREFIX = "product:stock:";
    private static final String USER_BUY_KEY_PREFIX = "user:buy:";
    // MQ队列key
    private static final String ORDER_QUEUE_KEY = "order:queue";
    // 死信队列key
    private static final String DEAD_LETTER_QUEUE_KEY = "dead:letter:queue";

    @Override
    public Order seckillWithCompensation(Long productId, Long userId) {
        // ==================== 1. 用户重复购买检查 ====================
        String userBuyKey = USER_BUY_KEY_PREFIX + productId + ":" + userId;
        Boolean isFirstBuy = stringRedisTemplate.opsForValue().setIfAbsent(userBuyKey, "1", 24, TimeUnit.HOURS);
        
        if (Boolean.FALSE.equals(isFirstBuy)) {
            throw new RuntimeException("您已经参与过该商品的秒杀，请勿重复购买");
        }

        // ==================== 2. Redis原子扣减库存 ====================
        String stockKey = STOCK_KEY_PREFIX + productId;
        Long stock = stringRedisTemplate.opsForValue().decrement(stockKey);
        
        if (stock == null) {
            Product product = productMapper.selectById(productId);
            if (product == null) {
                throw new RuntimeException("商品不存在");
            }
            stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(product.getStock()));
            stock = stringRedisTemplate.opsForValue().decrement(stockKey);
        }
        
        if (stock < 0) {
            // 库存不足，回滚Redis
            stringRedisTemplate.opsForValue().increment(stockKey);
            stringRedisTemplate.delete(userBuyKey);
            throw new RuntimeException("已售罄");
        }

        // ==================== 3. 发送MQ消息（异步处理订单） ====================
        Order order = new Order();
        order.setProductId(productId);
        order.setUserId(userId);
        order.setOrderNo(UUID.randomUUID().toString().replace("-", ""));
        
        Product product = productMapper.selectById(productId);
        order.setPrice(product.getPrice());
        order.setStatus(0);
        
        // 将订单信息发送到MQ队列
        // 这里用Redis List模拟MQ
        String orderJson = String.format("{\"orderNo\":\"%s\",\"productId\":%d,\"userId\":%d,\"price\":%s}",
            order.getOrderNo(), order.getProductId(), order.getUserId(), order.getPrice());
        
        stringRedisTemplate.opsForList().leftPush(ORDER_QUEUE_KEY, orderJson);
        System.out.printf("订单[%s]已发送到MQ队列%n", order.getOrderNo());

        return order;
    }

    /**
     * MQ消费者：异步处理订单
     * 模拟从队列中消费消息
     */
    @Async
    @EventListener
    public void consumeOrderMessage(String orderJson) {
        System.out.println("========== 开始消费订单消息 ==========");
        
        try {
            // 解析订单信息（实际项目中应该用JSON解析）
            // 这里简化处理
            System.out.println("订单信息：" + orderJson);
            
            // 模拟处理订单
            // 1. 解析订单信息
            // 2. 保存到数据库
            // 3. 更新库存
            
            System.out.println("订单处理成功");
            
        } catch (Exception e) {
            System.out.println("订单处理失败：" + e.getMessage());
            
            // 重试逻辑
            retryMessage(orderJson);
        }
    }

    /**
     * 重试消息
     */
    private void retryMessage(String orderJson) {
        // 简化处理：重新放回队列
        // 实际项目中应该有重试次数限制
        stringRedisTemplate.opsForList().leftPush(ORDER_QUEUE_KEY, orderJson);
        System.out.println("消息已重新入队，等待重试");
    }

    /**
     * 处理死信消息
     */
    private void handleDeadLetter(String orderJson) {
        System.out.println("处理死信消息：" + orderJson);
        // 记录日志、发送告警、人工处理等
    }
}
