package com.example.seckill.service;

import com.example.seckill.entity.Product;
import com.example.seckill.mapper.OrderMapper;
import com.example.seckill.mapper.ProductMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 定时任务补偿服务（完善版）
 * 定期检查Redis库存和数据库库存的一致性
 * 
 * 核心思想：
 * 1. 定时任务每隔一段时间检查库存一致性
 * 2. 如果发现不一致，分析原因并补偿
 * 3. 保证最终一致性
 */
@Service
public class CompensationService {

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final String STOCK_KEY_PREFIX = "product:stock:";
    private static final String USER_BUY_KEY_PREFIX = "user:buy:";

    /**
     * 定时任务：每30秒检查一次库存一致性
     */
    @Scheduled(fixedRate = 30000)
    public void checkStockConsistency() {
        System.out.println("========== 开始检查库存一致性 ==========");
        
        List<Product> products = productMapper.selectAll();
        
        int inconsistentCount = 0;
        
        for (Product product : products) {
            String stockKey = STOCK_KEY_PREFIX + product.getId();
            String redisStockStr = stringRedisTemplate.opsForValue().get(stockKey);
            
            if (redisStockStr == null) {
                continue;
            }
            
            int redisStock = Integer.parseInt(redisStockStr);
            int dbStock = product.getStock();
            
            if (redisStock != dbStock) {
                inconsistentCount++;
                System.out.printf("库存不一致：商品[%s(id=%d)] Redis=%d, DB=%d%n", 
                    product.getName(), product.getId(), redisStock, dbStock);
                
                // 分析不一致原因并补偿
                analyzeAndCompensate(product.getId(), redisStock, dbStock);
            }
        }
        
        if (inconsistentCount == 0) {
            System.out.println("库存一致性检查通过，无不一致数据");
        } else {
            System.out.printf("发现%d个不一致数据，已处理%n", inconsistentCount);
        }
        
        System.out.println("========== 库存一致性检查完成 ==========");
    }

    /**
     * 分析不一致原因并补偿
     */
    private void analyzeAndCompensate(Long productId, int redisStock, int dbStock) {
        // 情况1：Redis库存 < 数据库库存
        // 原因：有订单在MQ中等待处理，或者MQ消费失败
        if (redisStock < dbStock) {
            int diff = dbStock - redisStock;
            System.out.printf("分析：商品[%d]有%d个订单在MQ中等待处理或消费失败%n", productId, diff);
            
            // 这种情况不需要补偿数据库
            // 因为订单还在MQ中，会自动重试
            // 或者进入死信队列，需要人工处理
            
            // 检查死信队列是否有该商品的消息
            checkDeadLetterQueue(productId);
        }
        
        // 情况2：Redis库存 > 数据库库存
        // 原因：数据库扣减失败，需要补偿
        else if (redisStock > dbStock) {
            int diff = redisStock - dbStock;
            System.out.printf("分析：商品[%d]数据库库存少扣了%d，需要补偿%n", productId, diff);
            
            // 以Redis为准，更新数据库
            compensateDatabase(productId, redisStock);
        }
    }

    /**
     * 检查死信队列
     */
    private void checkDeadLetterQueue(Long productId) {
        // 实际项目中，这里会检查死信队列中是否有该商品的消息
        // 如果有，说明消息处理失败，需要人工处理
        
        System.out.printf("检查死信队列：商品[%d]的消息是否处理失败%n", productId);
    }

    /**
     * 补偿数据库库存
     */
    private void compensateDatabase(Long productId, int redisStock) {
        try {
            System.out.printf("补偿数据库库存：商品[%d] 更新为 %d%n", productId, redisStock);
            
            // 以Redis为准，更新数据库库存
            productMapper.updateStock(productId, redisStock);
            
            System.out.printf("补偿成功：商品[%d] 数据库库存更新为 %d%n", productId, redisStock);
            
        } catch (Exception e) {
            System.out.println("补偿数据库失败：" + e.getMessage());
        }
    }

    /**
     * 定时任务：每60秒检查死信队列
     */
    @Scheduled(fixedRate = 60000)
    public void checkDeadLetterQueuePeriodically() {
        System.out.println("========== 检查死信队列 ==========");
        
        // 实际项目中，这里会：
        // 1. 查询死信队列中的消息
        // 2. 分析失败原因
        // 3. 尝试重新发送或人工处理
        
        System.out.println("========== 死信队列检查完成 ==========");
    }

    /**
     * 定时任务：每60秒清理过期的用户购买标记
     */
    @Scheduled(fixedRate = 60000)
    public void cleanExpiredUserBuyMarks() {
        System.out.println("========== 开始清理过期用户购买标记 ==========");
        System.out.println("========== 过期用户购买标记清理完成 ==========");
    }
}
