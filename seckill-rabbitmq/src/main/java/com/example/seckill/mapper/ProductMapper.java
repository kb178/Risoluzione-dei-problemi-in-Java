package com.example.seckill.mapper;

import com.example.seckill.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProductMapper {
    @Select("SELECT * FROM product WHERE id = #{id}")
    Product selectById(@Param("id") Long id);

    @Select("SELECT * FROM product")
    java.util.List<Product> selectAll();

    @Update("UPDATE product SET stock = stock - 1 WHERE id = #{id} AND stock > 0")
    int decreaseStock(@Param("id") Long id);

    @Update("UPDATE product SET stock = #{stock} WHERE id = #{id}")
    int updateStock(@Param("id") Long id, @Param("stock") int stock);
}
