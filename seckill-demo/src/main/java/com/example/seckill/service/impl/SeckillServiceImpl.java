package com.example.seckill.service.impl;

import com.example.seckill.entity.Order;
import com.example.seckill.entity.Product;
import com.example.seckill.mapper.OrderMapper;
import com.example.seckill.mapper.ProductMapper;
import com.example.seckill.service.SeckillService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class SeckillServiceImpl implements SeckillService {

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private OrderMapper orderMapper;

    /**
     * 方案1：原始版本 - 有超卖漏洞
     * 问题：先查库存再扣库存，中间有并发窗口，会导致超卖
     * 教学目的：理解超卖问题的本质
     */
    @Override
    public Order seckillV1(Long productId, Long userId) {
        // 1. 查询商品信息（普通查询，不加锁）
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new RuntimeException("商品不存在");
        }

        // 2. 检查库存（并发时多个线程可能同时通过）
        if (product.getStock() <= 0) {
            throw new RuntimeException("库存不足");
        }

        // 3. 扣减库存（无锁保护，可能超卖）
        int updateRows = productMapper.decreaseStock(productId);
        if (updateRows == 0) {
            throw new RuntimeException("库存扣减失败，可能库存不足");
        }

        // 4. 生成订单
        Order order = new Order();
        order.setProductId(productId);
        order.setUserId(userId);
        order.setOrderNo(UUID.randomUUID().toString().replace("-", ""));
        order.setPrice(product.getPrice());
        order.setStatus(0);
        orderMapper.insert(order);

        return order;
    }

    /**
     * 方案2：@Transactional版本 - 仍然超卖
     * 教学目的：理解@Transactional不能解决并发问题
     * 原理：@Transactional只保证原子性，不能阻止并发读
     */
    @Override
    @Transactional
    public Order seckillV2(Long productId, Long userId) {
        // 1. 查询商品信息（普通查询，不加锁）
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new RuntimeException("商品不存在");
        }

        // 2. 检查库存（并发时仍然可能同时通过）
        if (product.getStock() <= 0) {
            throw new RuntimeException("库存不足");
        }

        // 3. 扣减库存（有事务保护，但无锁，仍可能超卖）
        int updateRows = productMapper.decreaseStock(productId);
        if (updateRows == 0) {
            throw new RuntimeException("库存扣减失败，可能库存不足");
        }

        // 4. 生成订单
        Order order = new Order();
        order.setProductId(productId);
        order.setUserId(userId);
        order.setOrderNo(UUID.randomUUID().toString().replace("-", ""));
        order.setPrice(product.getPrice());
        order.setStatus(0);
        orderMapper.insert(order);

        return order;
    }

    /**
     * 方案3：悲观锁版本 - 解决超卖，但性能差
     * 教学目的：理解悲观锁的原理和局限性
     * 原理：SELECT ... FOR UPDATE 在事务结束前一直持有行锁
     * 优点：简单可靠，能防止超卖
     * 缺点：性能差，所有请求串行执行，不适合高并发
     */
    @Override
    @Transactional
    public Order seckillV3悲观锁(Long productId, Long userId) {
        // 1. 使用悲观锁查询商品信息（SELECT ... FOR UPDATE）
        // 这行代码会加行锁，其他线程的SELECT ... FOR UPDATE会被阻塞，需要配合@Transactional，否者执行完查库存代码后就会释放锁
        Product product = productMapper.selectByIdForUpdate(productId);
        if (product == null) {
            throw new RuntimeException("商品不存在");
        }

        // 2. 检查库存（在锁保护下，其他线程无法修改）
        if (product.getStock() <= 0) {
            throw new RuntimeException("库存不足");
        }

        // 3. 扣减库存（在锁保护下）
        int updateRows = productMapper.decreaseStock(productId);
        if (updateRows == 0) {
            throw new RuntimeException("库存扣减失败，可能库存不足");
        }

        // 4. 生成订单（在锁保护下）
        Order order = new Order();
        order.setProductId(productId);
        order.setUserId(userId);
        order.setOrderNo(UUID.randomUUID().toString().replace("-", ""));
        order.setPrice(product.getPrice());
        order.setStatus(0);
        orderMapper.insert(order);

        return order;
        // 事务提交时释放锁
    }

    /**
     * 方案4：乐观锁版本 - 解决超卖，性能较好
     * 教学目的：理解乐观锁的原理和适用场景
     * 原理：使用版本号（version）机制，更新时检查版本号是否变化
     * 优点：读不加锁，性能好，适合读多写少
     * 缺点：写冲突时需要重试，高并发写时重试次数多
     */
    @Override
    public Order seckillV4乐观锁(Long productId, Long userId) {
        // 最大重试次数，防止无限循环
        int maxRetry = 3;

        for (int i = 0; i < maxRetry; i++) {
            // 1. 查询商品信息（普通查询，不加锁）
            Product product = productMapper.selectById(productId);
            if (product == null) {
                throw new RuntimeException("商品不存在");
            }

            // 2. 检查库存
            if (product.getStock() <= 0) {
                throw new RuntimeException("库存不足");
            }

            // 3. 乐观锁扣减库存
            // SQL: UPDATE product SET stock = stock - 1, version = version + 1
            //      WHERE id = #{id} AND version = #{version}
            // 如果version变化（被其他线程修改），则更新0行，需要重试
            int updateRows = productMapper.decreaseStockByVersion(
                productId, 
                product.getVersion()
            );

            if (updateRows > 0) {
                // 4. 扣减成功，生成订单
                Order order = new Order();
                order.setProductId(productId);
                order.setUserId(userId);
                order.setOrderNo(UUID.randomUUID().toString().replace("-", ""));
                order.setPrice(product.getPrice());
                order.setStatus(0);
                orderMapper.insert(order);

                return order;
            }

            // 版本冲突，重试（其他线程已经修改了库存）
            // 可以加一个短暂休眠，减少冲突
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // 重试次数用完，失败
        throw new RuntimeException("秒杀失败，请重试");
    }

    /**
     * 方案5：Redis预扣减版本 - 高并发方案（下一阶段学习）
     * 教学目的：理解生产环境的秒杀架构
     * 原理：先在Redis中预扣减，再异步处理订单
     * 优点：极高并发，用户体验好
     * 缺点：复杂度高，需要Redis和MQ
     */
    // TODO: 第二阶段实现
}
