package com.example.seckill.mapper;

import com.example.seckill.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;

@Mapper
public interface OrderMapper {
    @Insert("INSERT INTO order_info (product_id, user_id, order_no, price, status) VALUES (#{productId}, #{userId}, #{orderNo}, #{price}, #{status})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Order order);
}
