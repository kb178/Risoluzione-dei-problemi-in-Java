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
        //查找所有商品
        List<Product> products = productMapper.selectAll();
        //遍历
        for (Product product : products) {
            //用每一个商品id和product:stock:组合成redis的key
            String stockKey = STOCK_KEY_PREFIX + product.getId();

            // 修复漏洞10：每次启动都用DB的值覆盖Redis，保证Redis和DB库存一致
            redisService.initStock(stockKey, product.getStock());
            System.out.printf("商品[%s]库存已同步到Redis: %d%n", product.getName(), product.getStock());
        }

        System.out.println("========== 商品库存初始化完成 ==========");
    }
}
