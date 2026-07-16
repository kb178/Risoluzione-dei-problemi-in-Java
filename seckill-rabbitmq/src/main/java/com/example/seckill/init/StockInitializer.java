package com.example.seckill.init;

import com.example.seckill.entity.Product;
import com.example.seckill.mapper.ProductMapper;
import com.example.seckill.service.RedisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class StockInitializer implements CommandLineRunner {

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private RedisService redisService;

    private static final String STOCK_KEY_PREFIX = "product:stock:";

    @Override
    public void run(String... args) throws Exception {
        System.out.println("========== 开始初始化商品库存到Redis ==========");
        
        List<Product> products = productMapper.selectAll();
        
        for (Product product : products) {
            String stockKey = STOCK_KEY_PREFIX + product.getId();
            
            // 使用RedisService初始化库存
            Long currentStock = redisService.getStock(stockKey);
            
            if (currentStock == null) {
                redisService.initStock(stockKey, product.getStock());
                System.out.printf("商品[%s]库存初始化到Redis: %d%n", product.getName(), product.getStock());
            } else {
                System.out.printf("商品[%s]库存已存在Redis: %d，跳过%n", product.getName(), currentStock);
            }
        }
        
        System.out.println("========== 商品库存初始化完成 ==========");
    }
}
