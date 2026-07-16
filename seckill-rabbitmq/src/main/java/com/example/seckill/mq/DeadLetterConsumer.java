package com.example.seckill.mq;

import com.example.seckill.config.RabbitMQConfig;
import com.example.seckill.entity.OrderMessage;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 死信队列消费者
 * 处理重试多次仍然失败的消息
 * 
 * ACK模式：AUTO（Spring自动处理ACK）
 */
@Component
public class DeadLetterConsumer {

    @RabbitListener(queues = RabbitMQConfig.DEAD_LETTER_QUEUE)
    public void handleDeadLetter(OrderMessage message) {
        System.out.println("========== 收到死信消息 ==========");
        System.out.println("死信消息内容：" + message);
        
        // 死信消息处理策略：
        // 1. 记录日志，人工排查
        System.out.printf("死信消息需要人工处理：用户[%d]购买商品[%d]，订单号[%s]%n",
            message.getUserId(), message.getProductId(), message.getOrderNo());
        
        // 2. 实际项目中可以：
        //    - 发送告警通知（邮件、短信、钉钉等）
        //    - 保存到数据库，后续人工处理
        //    - 如果可以自动修复，重新发送到正常队列
        
        System.out.println("========== 死信消息处理完成 ==========");
    }
}
