package com.example.seckill.service.impl;

import com.example.seckill.entity.Order;
import com.example.seckill.entity.Product;
import com.example.seckill.mapper.OrderMapper;
import com.example.seckill.mapper.ProductMapper;
import com.example.seckill.service.SeckillService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class SeckillServiceImpl implements SeckillService {

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Override
    public Order seckill(Long productId, Long userId) {
        // 1. 查询商品信息
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new RuntimeException("商品不存在");
        }

        // 2. 检查库存（这里故意用简单判断，不考虑并发）
        if (product.getStock() <= 0) {
            throw new RuntimeException("库存不足");
        }

        // 3. 扣减库存（直接减1，不加锁）
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
        order.setStatus(0); // 成功
        orderMapper.insert(order);

        return order;
    }
}
