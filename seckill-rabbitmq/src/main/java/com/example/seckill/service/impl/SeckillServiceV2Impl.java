package com.example.seckill.service.impl;

import com.example.seckill.config.RabbitMQConfig;
import com.example.seckill.entity.OrderMessage;
import com.example.seckill.entity.Product;
import com.example.seckill.mapper.ProductMapper;
import com.example.seckill.service.SeckillService;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀服务V2实现（分布式锁方案）
 *
 * 与V1的区别：
 * V1：Lua脚本原子操作（无锁，靠Redis原子性保证）
 * V2：Redisson分布式锁（加锁，保证并发安全）
 *
 * 执行流程：
 * 1. 获取分布式锁（Redisson）
 * 2. 检查用户是否重复购买（Redis）
 * 3. 检查库存并扣减（Redis）
 * 4. 释放锁
 * 5. 发送MQ消息
 * 6. 返回"排队中"
 *
 * 分布式锁的作用：
 * - 防止同一用户并发请求重复下单
 * - 防止库存检查和扣减之间的竞态条件
 */
@Service("seckillServiceV2")
public class SeckillServiceV2Impl implements SeckillService {

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    private static final String STOCK_KEY_PREFIX = "product:stock:";
    private static final String USER_BUY_KEY_PREFIX = "user:buy:";
    private static final String LOCK_KEY_PREFIX = "seckill:lock:";

    // 用户标记过期时间：7天（秒）
    private static final long USER_BUY_EXPIRE_SECONDS = 604800;

    @Override
    public String seckill(Long productId, Long userId) {
        // ==================== 1. 获取分布式锁 ====================
        String lockKey = LOCK_KEY_PREFIX + productId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 尝试获取锁：最多等待5秒
            // leaseTime传-1：启用看门狗机制，默认30秒过期，每10秒自动续期
            // 业务执行时间超过30秒时看门狗会自动续期，不会手动释放
            if (!lock.tryLock(5, -1, TimeUnit.SECONDS)) {
                throw new IllegalStateException("系统繁忙，请稍后重试");
            }

            try {
                // ==================== 2. 检查用户是否重复购买 ====================
                String userBuyKey = USER_BUY_KEY_PREFIX + productId + ":" + userId;
                // 使用setIfAbsent原子操作：设置值+过期时间在一条命令中完成
                // 返回true表示设置成功（首次购买），false表示key已存在（重复购买）
                Boolean isFirstBuy = redissonClient.getBucket(userBuyKey)
                    .trySet("1", USER_BUY_EXPIRE_SECONDS, TimeUnit.SECONDS);

                if (Boolean.FALSE.equals(isFirstBuy)) {
                    throw new IllegalStateException("您已经参与过该商品的秒杀，请勿重复购买");
                }

                // ==================== 3. 检查库存并扣减 ====================
                String stockKey = STOCK_KEY_PREFIX + productId;
                Object stockObj = redissonClient.getBucket(stockKey).get();

                // 安全转换为Long，防止ClassCastException
                Long stock = null;
                if (stockObj != null) {
                    if (stockObj instanceof Long) {
                        stock = (Long) stockObj;
                    } else if (stockObj instanceof Number) {
                        stock = ((Number) stockObj).longValue();
                    } else {
                        stock = Long.parseLong(stockObj.toString());
                    }
                }

                if (stock == null) {
                    // Redis中没有库存，从数据库初始化
                    Product product = productMapper.selectById(productId);
                    if (product == null) {
                        // 回滚用户标记
                        redissonClient.getBucket(userBuyKey).delete();
                        throw new IllegalStateException("商品不存在");
                    }
                    // 设置库存
                    redissonClient.getBucket(stockKey).set((long) product.getStock());
                    stock = (long) product.getStock();
                }

                if (stock <= 0) {
                    // 库存不足，回滚用户标记
                    redissonClient.getBucket(userBuyKey).delete();
                    throw new IllegalStateException("已售罄");
                }

                // 扣减库存
                long newStock = stock - 1;
                redissonClient.getBucket(stockKey).set(newStock);
                System.out.println("Redis库存扣减成功，当前库存: " + newStock);

                // ==================== 4. 发送MQ消息 ====================
                String orderNo = UUID.randomUUID().toString().replace("-", "");
                Product product = productMapper.selectById(productId);
                BigDecimal price = product.getPrice();

                OrderMessage message = new OrderMessage(productId, userId, orderNo, price);

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
                    redissonClient.getBucket(stockKey).set(stock);  // 恢复库存
                    redissonClient.getBucket(userBuyKey).delete();  // 删除用户标记
                    throw new IllegalStateException("系统繁忙，请稍后重试");
                }

                return "排队中，订单号：" + orderNo;

            } finally {
                // ==================== 5. 释放锁 ====================
                // 只有当前线程持有锁时才释放，防止误删其他线程的锁
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }

        } catch (InterruptedException e) {
            // 中断异常：恢复中断状态，让上层感知
            Thread.currentThread().interrupt();
            throw new IllegalStateException("系统异常，请重试");
        }
    }
}
