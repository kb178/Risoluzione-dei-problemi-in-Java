package com.example.seckill.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Service;

/**
 * Redis操作服务
 * 使用Lua脚本保证操作的原子性
 */
@Service
public class RedisService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 秒杀Lua脚本
     */
    private static final String SECKILL_SCRIPT =
        "local stockKey = KEYS[1] " +
        "local userBuyKey = KEYS[2] " +
        "local expireTime = tonumber(ARGV[1]) " +
        "if redis.call('exists', userBuyKey) == 1 then " +
        "    return -1 " +
        "end " +
        "local stock = tonumber(redis.call('get', stockKey)) " +
        "if stock == nil then stock = 0 end " +
        "if stock <= 0 then " +
        "    return 0 " +
        "end " +
        "redis.call('decr', stockKey) " +
        "redis.call('set', userBuyKey, '1') " +
        "redis.call('expire', userBuyKey, expireTime) " +
        "return 1";

    /**
     * 执行秒杀操作（原子操作）
     */
    public Long seckill(String stockKey, String userBuyKey, long expireTime) {
        try {
            RedisSerializer<String> serializer = stringRedisTemplate.getStringSerializer();
            
            Long result = stringRedisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Long>) (connection) -> {
                // 序列化脚本和参数
                byte[] script = SECKILL_SCRIPT.getBytes();
                byte[] key1 = serializer.serialize(stockKey);
                byte[] key2 = serializer.serialize(userBuyKey);
                byte[] arg = serializer.serialize(String.valueOf(expireTime));
                
                // 执行Lua脚本，ReturnType.INTEGER表示返回数字
                Long evalResult = connection.eval(script, ReturnType.INTEGER, 2, key1, key2, arg);
                return evalResult;
            });
            
            return result;
        } catch (Exception e) {
            System.out.println("[ERROR] " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 回滚库存
     */
    public void rollback(String stockKey, String userBuyKey) {
        stringRedisTemplate.opsForValue().increment(stockKey);
        stringRedisTemplate.delete(userBuyKey);
    }

    /**
     * 初始化库存
     * 修复漏洞10：每次启动都用DB的值覆盖Redis，保证Redis和DB库存一致
     */
    public void initStock(String stockKey, int stock) {
        // 直接覆盖Redis库存，不管之前是什么值
        stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(stock));
    }

    /**
     * 获取库存
     */
    public Long getStock(String stockKey) {
        String stockStr = stringRedisTemplate.opsForValue().get(stockKey);
        return stockStr != null ? Long.parseLong(stockStr) : null;
    }
}
