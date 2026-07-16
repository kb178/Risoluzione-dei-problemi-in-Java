package com.example.seckill.service.impl;

import com.example.seckill.entity.Order;
import com.example.seckill.entity.Product;
import com.example.seckill.mapper.OrderMapper;
import com.example.seckill.mapper.ProductMapper;
import com.example.seckill.service.CompensationServiceV2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 补偿方案2：定时任务补偿
 * 
 * 核心思想：
 * 1. 秒杀时只扣减Redis库存，不立即写数据库
 * 2. 定时任务定期检查Redis和数据库的不一致数据
 * 3. 对不一致的数据进行补偿（补订单或补库存）
 * 
 * 优点：
 * - 支持异步操作（MQ）
 * - 解耦Redis和数据库
 * - 最终一致性
 * 
 * 缺点：
 * - 实现复杂
 * - 有延迟（定时任务间隔）
 * - 需要额外的状态标记
 */
@Service
public class CompensationServiceV2Impl implements CompensationServiceV2 {

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final String STOCK_KEY_PREFIX = "product:stock:";
    private static final String USER_BUY_KEY_PREFIX = "user:buy:";
    // 新增：待处理订单队列
    private static final String PENDING_ORDER_KEY_PREFIX = "pending:order:";

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

        // ==================== 3. 生成订单（只记录到Redis，异步处理） ====================
        Order order = new Order();
        order.setProductId(productId);
        order.setUserId(userId);
        order.setOrderNo(UUID.randomUUID().toString().replace("-", ""));
        
        Product product = productMapper.selectById(productId);
        order.setPrice(product.getPrice());
        order.setStatus(0);
        
        // 将订单信息保存到Redis（待处理队列）
        String pendingOrderKey = PENDING_ORDER_KEY_PREFIX + order.getOrderNo();
        stringRedisTemplate.opsForValue().set(pendingOrderKey, order.getOrderNo(), 1, TimeUnit.HOURS);
        
        System.out.printf("订单[%s]已记录到Redis，等待定时任务处理%n", order.getOrderNo());
        
        // 注意：这里没有写数据库，由定时任务完成
        return order;
    }

    /**
     * 定时任务：每5秒执行一次，检查并补偿不一致数据
     */
    @Override
    @Scheduled(fixedRate = 5000)
    public void checkAndCompensate() {
        System.out.println("========== 开始检查不一致数据 ==========");
        
        // 1. 检查Redis库存和数据库库存是否一致
        checkStockConsistency();
        
        // 2. 检查待处理订单，补写数据库
        processPendingOrders();
        
        System.out.println("========== 不一致数据检查完成 ==========");
    }

    /**
     * 检查库存一致性
     */
    private void checkStockConsistency() {
        // 查询所有商品
        java.util.List<Product> products = productMapper.selectAll();
        
        for (Product product : products) {
            String stockKey = STOCK_KEY_PREFIX + product.getId();
            String redisStockStr = stringRedisTemplate.opsForValue().get(stockKey);
            
            if (redisStockStr != null) {
                int redisStock = Integer.parseInt(redisStockStr);
                int dbStock = product.getStock();
                
                if (redisStock != dbStock) {
                    System.out.printf("库存不一致：商品[%s] Redis=%d, DB=%d%n", 
                        product.getName(), redisStock, dbStock);
                    
                    // 补偿策略：以Redis为准，更新数据库
                    productMapper.updateStock(product.getId(), redisStock);
                    System.out.printf("已补偿：商品[%s] 数据库库存更新为 %d%n", 
                        product.getName(), redisStock);
                }
            }
        }
    }

    /**
     * 处理待处理订单
     */
    private void processPendingOrders() {
        // 这里简化处理，实际应该用SCAN命令遍历
        // 实际项目中可以用Redis List或Stream来管理待处理订单
        
        // 模拟处理：检查是否有Redis库存但无订单的情况
        java.util.List<Product> products = productMapper.selectAll();
        
        for (Product product : products) {
            String stockKey = STOCK_KEY_PREFIX + product.getId();
            String redisStockStr = stringRedisTemplate.opsForValue().get(stockKey);
            
            if (redisStockStr != null) {
                int redisStock = Integer.parseInt(redisStockStr);
                int dbStock = product.getStock();
                
                // 如果Redis库存比数据库少，说明有订单未处理
                if (redisStock < dbStock) {
                    int diff = dbStock - redisStock;
                    System.out.printf("发现%d个未处理订单：商品[%s]%n", diff, product.getName());
                    
                    // 这里可以补充创建订单的逻辑
                    // 实际项目中需要从Redis的待处理队列中获取订单信息
                }
            }
        }
    }
}
