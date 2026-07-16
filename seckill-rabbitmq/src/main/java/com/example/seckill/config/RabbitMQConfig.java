package com.example.seckill.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * RabbitMQ配置类
 * 声明队列、交换机、绑定关系
 * 包含死信队列配置
 */
@Configuration
public class RabbitMQConfig {

    // ==================== 正常队列配置 ====================
    public static final String ORDER_QUEUE = "seckill.order.queue";
    public static final String ORDER_EXCHANGE = "seckill.order.exchange";
    public static final String ORDER_ROUTING_KEY = "seckill.order";

    // ==================== 死信队列配置 ====================
    public static final String DEAD_LETTER_QUEUE = "seckill.order.dead.queue";
    public static final String DEAD_LETTER_EXCHANGE = "seckill.order.dead.exchange";
    public static final String DEAD_LETTER_ROUTING_KEY = "seckill.order.dead";

    /**
     * 声明死信交换机
     */
    @Bean
    public DirectExchange deadLetterExchange() {
        return ExchangeBuilder.directExchange(DEAD_LETTER_EXCHANGE)
                .durable(true)
                .build();
    }

    /**
     * 声明死信队列
     */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    /**
     * 绑定死信队列到死信交换机
     */
    @Bean
    public Binding deadLetterBinding(Queue deadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue)
                .to(deadLetterExchange)
                .with(DEAD_LETTER_ROUTING_KEY);
    }

    /**
     * 声明订单队列（绑定死信交换机）
     * 消息被拒绝时，会发送到死信交换机
     */
    @Bean
    public Queue orderQueue() {
        Map<String, Object> args = new HashMap<>();
        // 设置死信交换机
        args.put("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE);
        // 设置死信路由键
        args.put("x-dead-letter-routing-key", DEAD_LETTER_ROUTING_KEY);
        // 设置消息TTL（可选）：消息在队列中存活时间，超过后进入死信队列
        // args.put("x-message-ttl", 60000); // 60秒

        return QueueBuilder.durable(ORDER_QUEUE).withArguments(args).build();
    }

    /**
     * 声明订单交换机
     */
    @Bean
    public DirectExchange orderExchange() {
        return ExchangeBuilder.directExchange(ORDER_EXCHANGE)
                .durable(true)
                .build();
    }

    /**
     * 绑定订单队列到订单交换机
     */
    @Bean
    public Binding orderBinding(Queue orderQueue, DirectExchange orderExchange) {
        return BindingBuilder.bind(orderQueue)
                .to(orderExchange)
                .with(ORDER_ROUTING_KEY);
    }

    /**
     * 配置消息转换器（JSON格式）
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 配置RabbitTemplate使用JSON转换器
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(jsonMessageConverter());
        return rabbitTemplate;
    }

    /**
     * 配置消费者监听器工厂，使用JSON转换器
     * 关键：消费者需要用与生产者相同的转换器反序列化消息
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter());
        return factory;
    }
}
