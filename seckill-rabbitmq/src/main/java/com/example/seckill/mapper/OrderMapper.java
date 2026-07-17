package com.example.seckill.mapper;

import com.example.seckill.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

@Mapper
public interface OrderMapper {
    //插入订单
    @Insert("INSERT INTO order_info (product_id, user_id, order_no, price, status) VALUES (#{productId}, #{userId}, #{orderNo}, #{price}, #{status})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Order order);

    //插入订单（简化方法，直接传参数）
    @Insert("INSERT INTO order_info (product_id, user_id, order_no, price, status) VALUES (#{productId}, #{userId}, #{orderNo}, #{price}, #{status})")
    int insertOrder(@Param("productId") Long productId,
                    @Param("userId") Long userId,
                    @Param("orderNo") String orderNo,
                    @Param("price") BigDecimal price,
                    @Param("status") Integer status);

    //查看用户购买订单数量
    @Select("SELECT COUNT(*) FROM order_info WHERE user_id = #{userId} AND product_id = #{productId}")
    int countByUserAndProduct(@Param("userId") Long userId, @Param("productId") Long productId);

    //根据订单号查询订单数量（用于幂等性检查）
    @Select("SELECT COUNT(*) FROM order_info WHERE order_no = #{orderNo}")
    int countByOrderNo(@Param("orderNo") String orderNo);
}
