package com.example.seckill.mq;

import com.example.seckill.config.RabbitMQConfig;
import com.example.seckill.entity.Order;
import com.example.seckill.entity.OrderMessage;
import com.example.seckill.mapper.OrderMapper;
import com.example.seckill.mapper.ProductMapper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * MQ消费者
 * 监听订单队列，处理订单生成逻辑
 * 
 * ACK模式：AUTO（Spring自动处理ACK）
 * - 方法正常返回 → 自动ack
 * - 方法抛出异常 → 自动nack并重新入队
 */
@Component
public class OrderConsumer {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private ProductMapper productMapper;

    @RabbitListener(queues = RabbitMQConfig.ORDER_QUEUE)
    @Transactional
    public void handleOrder(OrderMessage message) {
        System.out.println("========== 收到订单消息 ==========");
        System.out.println("消息内容：" + message);
        
        // 1. 幂等性检查（防止重复消费）
        int count = orderMapper.countByUserAndProduct(message.getUserId(), message.getProductId());
        if (count > 0) {
            System.out.printf("用户[%d]已购买过商品[%d]，跳过重复消费%n", 
                message.getUserId(), message.getProductId());
            return;  // AUTO模式：正常返回自动ack
        }
        System.out.printf("幂等性检查通过：用户[%d]首次购买商品[%d]%n", 
            message.getUserId(), message.getProductId());

        // 2. 扣减MySQL库存
        int updateRows = productMapper.decreaseStock(message.getProductId());
        if (updateRows == 0) {
            System.out.printf("商品[%d]库存不足，扣减失败%n", message.getProductId());
            return;  // AUTO模式：正常返回自动ack（库存不足不需要重试）
        }
        System.out.printf("MySQL库存扣减成功：商品[%d]%n", message.getProductId());

        // 3. 插入订单表
        Order order = new Order();
        order.setProductId(message.getProductId());
        order.setUserId(message.getUserId());
        order.setOrderNo(message.getOrderNo());
        order.setPrice(message.getPrice());
        order.setStatus(0); // 成功
        
        orderMapper.insert(order);
        System.out.printf("订单插入成功：订单号[%s]%n", message.getOrderNo());

        System.out.printf("消息处理完成：订单号[%s]%n", message.getOrderNo());
        // AUTO模式：方法正常返回，Spring自动ack
        // 如果上面任何步骤抛异常，Spring自动nack并重新入队
        
        System.out.println("========== 订单消息处理完成 ==========");
    }
}
