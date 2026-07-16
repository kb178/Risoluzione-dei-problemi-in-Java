package com.example.seckill.entity;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 订单消息实体
 * 用于在MQ中传输订单信息
 */
public class OrderMessage implements Serializable {
    
    private Long productId;
    private Long userId;
    private String orderNo;
    private BigDecimal price;
    private Integer retryCount;  // 重试次数
    
    public OrderMessage() {}
    
    public OrderMessage(Long productId, Long userId, String orderNo, BigDecimal price) {
        this.productId = productId;
        this.userId = userId;
        this.orderNo = orderNo;
        this.price = price;
        this.retryCount = 0;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    @Override
    public String toString() {
        return "OrderMessage{" +
                "productId=" + productId +
                ", userId=" + userId +
                ", orderNo='" + orderNo + '\'' +
                ", price=" + price +
                ", retryCount=" + retryCount +
                '}';
    }
}
