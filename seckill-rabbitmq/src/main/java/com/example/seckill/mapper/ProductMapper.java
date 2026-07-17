package com.example.seckill.mapper;

import com.example.seckill.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProductMapper {
    //根据id查找对应商品
    @Select("SELECT * FROM product WHERE id = #{id}")
    Product selectById(@Param("id") Long id);
    //查找全部商品
    @Select("SELECT * FROM product")
    java.util.List<Product> selectAll();
    //扣减商品
    @Update("UPDATE product SET stock = stock - 1 WHERE id = #{id} AND stock > 0")
    int decreaseStock(@Param("id") Long id);
    //扣减库存（指定数量）
    @Update("UPDATE product SET stock = stock - #{quantity} WHERE id = #{id} AND stock >= #{quantity}")
    int deductStock(@Param("id") Long id, @Param("quantity") int quantity);
    //根据更新商品库存
    @Update("UPDATE product SET stock = #{stock} WHERE id = #{id}")
    int updateStock(@Param("id") Long id, @Param("stock") int stock);

    //增加库存（用于并发竞态时回滚）
    @Update("UPDATE product SET stock = stock + 1 WHERE id = #{id}")
    int increaseStock(@Param("id") Long id);
}
