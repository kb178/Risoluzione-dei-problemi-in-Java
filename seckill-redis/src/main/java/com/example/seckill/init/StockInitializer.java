package com.example.seckill.init;

import com.example.seckill.entity.Product;
import com.example.seckill.mapper.ProductMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 项目启动时，将数据库中的商品库存加载到Redis
 * 使用CommandLineRunner，在Spring容器启动完成后执行
 */
@Component
public class StockInitializer implements CommandLineRunner {

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // Redis key前缀，与Service中保持一致
    private static final String STOCK_KEY_PREFIX = "product:stock:";

    @Override
    public void run(String... args) throws Exception {
        System.out.println("========== 开始初始化商品库存到Redis ==========");
        
        // 查询所有商品
        // 这里简化处理，实际应该分页查询
        List<Product> products = productMapper.selectAll();
        
        for (Product product : products) {
            String stockKey = STOCK_KEY_PREFIX + product.getId();
            String currentStock = stringRedisTemplate.opsForValue().get(stockKey);
            
            if (currentStock == null) {
                // Redis中没有该商品的库存，初始化
                stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(product.getStock()));
                System.out.printf("商品[%s]库存初始化到Redis: %d%n", product.getName(), product.getStock());
            } else {
                System.out.printf("商品[%s]库存已存在Redis: %s，跳过%n", product.getName(), currentStock);
            }
        }
        
        System.out.println("========== 商品库存初始化完成 ==========");
    }
}
