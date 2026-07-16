package com.example.seckill.service.impl;

import com.example.seckill.config.RabbitMQConfig;
import com.example.seckill.entity.OrderMessage;
import com.example.seckill.entity.Product;
import com.example.seckill.mapper.ProductMapper;
import com.example.seckill.service.RedisService;
import com.example.seckill.service.SeckillService;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class SeckillServiceImpl implements SeckillService {

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private RedisService redisService;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    // ==================== 保留原来的StringRedisTemplate（用于学习对比） ====================
    // @Autowired
    // private StringRedisTemplate stringRedisTemplate;

    private static final String STOCK_KEY_PREFIX = "product:stock:";
    private static final String USER_BUY_KEY_PREFIX = "user:buy:";

    @Override
    public String seckill(Long productId, Long userId) {
        // ==================== 1. Redis原子扣减库存（Lua脚本） ====================
        String stockKey = STOCK_KEY_PREFIX + productId;
        String userBuyKey = USER_BUY_KEY_PREFIX + productId + ":" + userId;
        
        // Lua脚本原子操作：检查库存 + 扣减库存 + 设置用户标记
        Long result = redisService.seckill(stockKey, userBuyKey, 86400);  // 24小时过期
        
        // 使用long基本类型避免Long包装类的NPE和比较问题
        long code = (result != null) ? result.longValue() : -99;
        
        switch ((int) code) {
            case -1:
                throw new IllegalStateException("您已经参与过该商品的秒杀，请勿重复购买");
            case 0:
                throw new IllegalStateException("已售罄");
            case 1:
                // 成功，继续处理
                break;
            default:
                throw new IllegalStateException("系统异常，请重试");
        }

        // ==================== 2. 获取当前库存（用于显示） ====================
        Long stock = redisService.getStock(stockKey);
        System.out.println("Redis库存扣减成功，当前库存: " + stock);

        // ==================== 3. 发送MQ消息（异步处理订单） ====================
        String orderNo = UUID.randomUUID().toString().replace("-", "");
        
        // 获取商品价格（防止空指针）
        Product product = productMapper.selectById(productId);
        if (product == null) {
            // 回滚Redis库存和用户标记
            redisService.rollback(stockKey, userBuyKey);
            throw new IllegalStateException("商品不存在");
        }
        BigDecimal price = product.getPrice();
        
        // 构建消息
        OrderMessage message = new OrderMessage(productId, userId, orderNo, price);
        
        // 发送消息到MQ
        try {
            rabbitTemplate.convertAndSend(
                RabbitMQConfig.ORDER_EXCHANGE,
                RabbitMQConfig.ORDER_ROUTING_KEY,
                message
            );
            System.out.printf("用户[%d]秒杀商品[%d]成功，订单[%s]已发送到MQ队列%n", 
                userId, productId, orderNo);
        } catch (Exception e) {
            // MQ发送失败，回滚Redis
            System.out.println("MQ发送失败，回滚Redis：" + e.getMessage());
            redisService.rollback(stockKey, userBuyKey);
            throw new IllegalStateException("系统繁忙，请稍后重试");
        }

        // ==================== 4. 立即返回"排队中" ====================
        return "排队中，订单号：" + orderNo;
    }

    // ==================== 保留原来的Redis操作代码（有并发问题，用于学习对比） ====================
    // 
    // 【问题代码】多次Redis调用，不是原子操作，存在并发问题：
    // 
    // @Override
    // public String seckill(Long productId, Long userId) {
    //     // 1. 用户重复购买检查（第一次Redis调用）
    //     String userBuyKey = USER_BUY_KEY_PREFIX + productId + ":" + userId;
    //     Boolean isFirstBuy = stringRedisTemplate.opsForValue().setIfAbsent(userBuyKey, "1", 24, TimeUnit.HOURS);
    //     
    //     if (Boolean.FALSE.equals(isFirstBuy)) {
    //         throw new RuntimeException("您已经参与过该商品的秒杀，请勿重复购买");
    //     }
    //
    //     // 2. Redis扣减库存（第二次Redis调用）← 如果这里宕机，用户标记已设置但库存没扣！
    //     String stockKey = STOCK_KEY_PREFIX + productId;
    //     Long stock = stringRedisTemplate.opsForValue().decrement(stockKey);
    //     
    //     if (stock == null) {
    //         Product product = productMapper.selectById(productId);
    //         if (product == null) {
    //             throw new RuntimeException("商品不存在");
    //         }
    //         stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(product.getStock()));
    //         stock = stringRedisTemplate.opsForValue().decrement(stockKey);
    //     }
    //     
    //     if (stock < 0) {
    //         // 库存不足，回滚Redis
    //         stringRedisTemplate.opsForValue().increment(stockKey);
    //         stringRedisTemplate.delete(userBuyKey);
    //         throw new RuntimeException("已售罄");
    //     }
    //
    //     // 3. 发送MQ消息（第三次Redis调用之后）
    //     // ... 代码同上
    // }
    //
    // 【问题分析】
    // 三次Redis调用不是原子的：
    // - 第1次：setIfAbsent（设置用户标记）
    // - 第2次：decrement（扣减库存）← 如果这里宕机
    // - 第3次：发送MQ消息
    //
    // 宕机后果：
    // - 用户标记已设置（用户无法再购买）
    // - 库存没扣（或者扣了但没发MQ）
    // - 数据不一致！
    //
    // 【解决方案】使用Lua脚本，保证原子性
    // - 一次Redis调用完成所有操作
    // - 要么全成功，要么全失败
    // - 见 RedisService.java 中的 Lua 脚本
}
