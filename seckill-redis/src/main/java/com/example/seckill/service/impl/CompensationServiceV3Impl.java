package com.example.seckill.service.impl;

import com.example.seckill.entity.Order;
import com.example.seckill.entity.Product;
import com.example.seckill.mapper.OrderMapper;
import com.example.seckill.mapper.ProductMapper;
import com.example.seckill.service.CompensationServiceV3;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 补偿方案3：状态机补偿
 * 
 * 核心思想：
 * 1. 订单有多个状态：INIT -> STOCK_DEDUCTED -> DB_SAVED -> COMPLETED
 * 2. 每个状态记录进度，失败时可以从断点重试
 * 3. 定时任务重试失败的订单
 * 
 * 订单状态流转：
 * INIT(0) -> STOCK_DEDUCTED(1) -> DB_SAVED(2) -> COMPLETED(3)
 *    |              |                   |
 *    +--失败--------+--失败-------------+--失败
 * 
 * 优点：
 * - 支持断点重试
 * - 状态清晰，易于调试
 * - 支持人工干预
 * 
 * 缺点：
 * - 实现复杂
 * - 需要额外的数据库字段
 * - 状态管理开销
 */
@Service
public class CompensationServiceV3Impl implements CompensationServiceV3 {

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final String STOCK_KEY_PREFIX = "product:stock:";
    private static final String USER_BUY_KEY_PREFIX = "user:buy:";
    // 订单状态key前缀
    private static final String ORDER_STATUS_KEY_PREFIX = "order:status:";

    // 订单状态常量
    private static final int STATUS_INIT = 0;
    private static final int STATUS_STOCK_DEDUCTED = 1;
    private static final int STATUS_DB_SAVED = 2;
    private static final int STATUS_COMPLETED = 3;

    @Override
    public Order seckillWithCompensation(Long productId, Long userId) {
        // 生成订单号
        String orderNo = UUID.randomUUID().toString().replace("-", "");
        String orderStatusKey = ORDER_STATUS_KEY_PREFIX + orderNo;

        try {
            // ==================== 阶段1：初始化订单状态 ====================
            stringRedisTemplate.opsForValue().set(orderStatusKey, String.valueOf(STATUS_INIT));
            System.out.printf("订单[%s]状态：INIT%n", orderNo);

            // ==================== 2. 用户重复购买检查 ====================
            String userBuyKey = USER_BUY_KEY_PREFIX + productId + ":" + userId;
            Boolean isFirstBuy = stringRedisTemplate.opsForValue().setIfAbsent(userBuyKey, "1", 24, TimeUnit.HOURS);
            
            if (Boolean.FALSE.equals(isFirstBuy)) {
                throw new RuntimeException("您已经参与过该商品的秒杀，请勿重复购买");
            }

            // ==================== 阶段2：Redis扣减库存 ====================
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

            // 更新状态：库存已扣减
            stringRedisTemplate.opsForValue().set(orderStatusKey, String.valueOf(STATUS_STOCK_DEDUCTED));
            System.out.printf("订单[%s]状态：STOCK_DEDUCTED, 库存=%d%n", orderNo, stock);

            // ==================== 阶段3：写数据库 ====================
            Order order = new Order();
            order.setProductId(productId);
            order.setUserId(userId);
            order.setOrderNo(orderNo);
            
            Product product = productMapper.selectById(productId);
            order.setPrice(product.getPrice());
            order.setStatus(0);
            
            // 保存订单到数据库
            orderMapper.insert(order);
            
            // 同步扣减数据库库存
            productMapper.updateStock(productId, stock.intValue());

            // 更新状态：数据库已保存
            stringRedisTemplate.opsForValue().set(orderStatusKey, String.valueOf(STATUS_DB_SAVED));
            System.out.printf("订单[%s]状态：DB_SAVED%n", orderNo);

            // ==================== 阶段4：完成 ====================
            stringRedisTemplate.opsForValue().set(orderStatusKey, String.valueOf(STATUS_COMPLETED));
            stringRedisTemplate.expire(orderStatusKey, 24, TimeUnit.HOURS); // 24小时后自动清理
            System.out.printf("订单[%s]状态：COMPLETED%n", orderNo);

            return order;

        } catch (Exception e) {
            // 记录失败状态，等待重试
            System.out.printf("订单[%s]失败：%s%n", orderNo, e.getMessage());
            // 不删除状态key，由定时任务重试
            throw new RuntimeException("秒杀失败，请重试", e);
        }
    }

    /**
     * 定时任务：重试失败的订单
     * 每10秒执行一次
     */
    @Override
    @Scheduled(fixedRate = 10000)
    public void retryFailedOrders() {
        System.out.println("========== 开始重试失败订单 ==========");
        
        // 这里简化处理，实际应该用SCAN命令遍历
        // 实际项目中应该查询数据库中status=0的订单
        
        // 模拟重试逻辑
        // 1. 查询数据库中status=0的订单
        // 2. 检查Redis中的状态
        // 3. 根据状态决定重试哪个阶段
        
        System.out.println("========== 失败订单重试完成 ==========");
    }
}
