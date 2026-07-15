package com.example.seckill.mapper;

import com.example.seckill.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ProductMapper {
    @Select("SELECT * FROM product WHERE id = #{id}")
    Product selectById(@Param("id") Long id);
    // 故意去掉 stock > 0 条件，制造超卖漏洞
    //@Update("UPDATE product SET stock = stock - 1 WHERE id = #{id} AND stock > 0") 隐式保护，在数据库执行会串行执行只有stack大于0才去执行
    @Update("UPDATE product SET stock = stock - 1 WHERE id = #{id}")
    int decreaseStock(@Param("id") Long id);
}
