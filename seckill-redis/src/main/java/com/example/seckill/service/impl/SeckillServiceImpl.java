package com.example.seckill.service.impl;

import com.example.seckill.entity.Order;
import com.example.seckill.entity.Product;
import com.example.seckill.mapper.OrderMapper;
import com.example.seckill.mapper.ProductMapper;
import com.example.seckill.service.SeckillService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class SeckillServiceImpl implements SeckillService {

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // Redis key前缀
    private static final String STOCK_KEY_PREFIX = "product:stock:";
    private static final String USER_BUY_KEY_PREFIX = "user:buy:";

    @Override
    public Order seckill(Long productId, Long userId) {
        // ==================== 1. 用户重复购买检查 ====================
        String userBuyKey = USER_BUY_KEY_PREFIX + productId + ":" + userId;
        // setIfAbsent：如果key不存在则设置，返回true；如果已存在则返回false
        Boolean isFirstBuy = stringRedisTemplate.opsForValue().setIfAbsent(userBuyKey, "1", 24, TimeUnit.HOURS);
        
        if (Boolean.FALSE.equals(isFirstBuy)) {
            throw new RuntimeException("您已经参与过该商品的秒杀，请勿重复购买");
        }
        // ==================== 用户标记结束 ====================

        // ==================== 2. Redis原子扣减库存 ====================
        String stockKey = STOCK_KEY_PREFIX + productId;
        // decrement是原子操作：将key的值减1，返回减后的值
        Long stock = stringRedisTemplate.opsForValue().decrement(stockKey);
        
        if (stock == null) {
            // Redis中没有该商品的库存，重新加载
            Product product = productMapper.selectById(productId);
            if (product == null) {
                throw new RuntimeException("商品不存在");
            }
            // 初始化库存到Redis
            stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(product.getStock()));
            // 重新扣减
            stock = stringRedisTemplate.opsForValue().decrement(stockKey);
        }
        
        if (stock < 0) {
            // 库存不足，回滚Redis（加回去）
            stringRedisTemplate.opsForValue().increment(stockKey);
            // 删除用户购买标记，允许其他用户购买
            stringRedisTemplate.delete(userBuyKey);
            throw new RuntimeException("已售罄");
        }
        // ==================== 原子扣减结束 ====================

        // ==================== 3. 生成订单 ====================
        Order order = new Order();
        order.setProductId(productId);
        order.setUserId(userId);
        order.setOrderNo(UUID.randomUUID().toString().replace("-", ""));
        
        // 获取商品价格
        Product product = productMapper.selectById(productId);
        order.setPrice(product.getPrice());
        order.setStatus(0); // 成功
        
        // 保存订单到数据库
        orderMapper.insert(order);
        
        // 同步扣减数据库库存（可选，也可异步）
        productMapper.updateStock(productId, stock.intValue());
        
        return order;
    }
}
