package com.example.seckill.mapper;

import com.example.seckill.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OrderMapper {
    @Insert("INSERT INTO order_info (product_id, user_id, order_no, price, status) VALUES (#{productId}, #{userId}, #{orderNo}, #{price}, #{status})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Order order);

    @Select("SELECT COUNT(*) FROM order_info WHERE user_id = #{userId} AND product_id = #{productId}")
    int countByUserAndProduct(@Param("userId") Long userId, @Param("productId") Long productId);
}
