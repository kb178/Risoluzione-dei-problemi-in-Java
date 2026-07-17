package com.example.seckill.service.impl;

import com.example.seckill.entity.Order;
import com.example.seckill.entity.Product;
import com.example.seckill.mapper.OrderMapper;
import com.example.seckill.mapper.ProductMapper;
import com.example.seckill.service.CompensationServiceV1;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 补偿方案1：事务补偿
 * 
 * 核心思想：
 * 1. Redis扣库存和数据库操作放在同一个"逻辑事务"中
 * 2. 如果数据库操作失败，立即回滚Redis
 * 3. 使用try-catch捕获异常，确保回滚
 * 
 * 优点：
 * - 实现简单，逻辑清晰
 * - 实时补偿，数据一致性好
 * 
 * 缺点：
 * - 如果回滚也失败，数据不一致
 * - 不支持异步操作（如MQ）
 */
@Service
public class CompensationServiceV1Impl implements CompensationServiceV1 {

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final String STOCK_KEY_PREFIX = "product:stock:";
    private static final String USER_BUY_KEY_PREFIX = "user:buy:";

    @Override
    @Transactional
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

        // ==================== 3. 生成订单（数据库操作） ====================
        try {
            Order order = new Order();
            order.setProductId(productId);
            order.setUserId(userId);
            order.setOrderNo(UUID.randomUUID().toString().replace("-", ""));
            
            Product product = productMapper.selectById(productId);
            order.setPrice(product.getPrice());
            order.setStatus(0);
            
            // 保存订单到数据库
            orderMapper.insert(order);
            
            // 同步扣减数据库库存
            productMapper.updateStock(productId, stock.intValue());
            
            return order;
            
        } catch (Exception e) {
            // ==================== 4. 数据库操作失败，回滚Redis ====================
            System.out.println("数据库操作失败，开始回滚Redis...");
            
            // 回滚库存
            stringRedisTemplate.opsForValue().increment(stockKey);
            System.out.printf("库存已回滚，当前库存: %s%n", stringRedisTemplate.opsForValue().get(stockKey));
            
            // 删除用户购买标记
            stringRedisTemplate.delete(userBuyKey);
            System.out.println("用户购买标记已删除");
            
            // 抛出异常，事务回滚
            throw new RuntimeException("秒杀失败，请重试", e);
        }
    }
}
