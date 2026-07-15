package com.example.seckill.mapper;

import com.example.seckill.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;

/**
 * 为什么数据库防护不够？
 * 1. 性能问题
 * // 当前代码的问题
 * 1. 查库存 → 打数据库
 * 2. 判断库存 > 0 → 打数据库
 * 3. 扣库存 → 打数据库
 * 4. 生成订单 → 打数据库
 * 4次数据库操作，1000个并发 = 4000次数据库请求，数据库直接崩溃。
 * 2. 用户体验问题
 * - 库存=10，1000人抢
 * - 990人白忙活：查询→判断→扣减→失败→重试
 * - 服务器资源全部浪费在无效操作上
 * 3. 业务漏洞
 * // 当前代码还有漏洞
 * if (product.getStock() <= 0) {
 *     throw new RuntimeException("库存不足");
 * }
 * 并发时：100个请求同时查到 stock=1，都通过检查，然后99个扣减失败。
 *
 */

@Mapper
public interface ProductMapper {
    @Select("SELECT * FROM product WHERE id = #{id}")
    Product selectById(@Param("id") Long id);

    // 悲观锁：查询时加行锁，其他线程等待
    @Select("SELECT * FROM product WHERE id = #{id} FOR UPDATE")
    Product selectByIdForUpdate(@Param("id") Long id);

    // 故意去掉 stock > 0 条件，制造超卖漏洞
    //@Update("UPDATE product SET stock = stock - 1 WHERE id = #{id} AND stock > 0") 隐式保护，在数据库执行会串行执行只有stack大于0才去执行
    @Update("UPDATE product SET stock = stock - 1 WHERE id = #{id}")
    int decreaseStock(@Param("id") Long id);

    // 乐观锁：更新时检查版本号，如果版本号变化则更新失败
    // SQL: UPDATE product SET stock = stock - 1, version = version + 1
    //      WHERE id = #{id} AND version = #{version}
    @Update("UPDATE product SET stock = stock - 1, version = version + 1 WHERE id = #{id} AND version = #{version}")
    int decreaseStockByVersion(@Param("id") Long id, @Param("version") Integer version);
}
