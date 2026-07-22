package com.shorturl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shorturl.entity.ShortUrl;
import com.shorturl.mapper.ShortUrlMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class ShortUrlService {

    private final ShortUrlMapper shortUrlMapper;
    private final StringRedisTemplate redisTemplate;

    // 短码字符集 (Base62)
    private static final String BASE62_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    // Redis缓存过期时间（小时）
    private static final long CACHE_EXPIRE_HOURS = 24;

    // Redis中存储自增ID的key
    private static final String ID_KEY = "short:url:id";

    /**
     * 生成短链接
     * @param originalUrl 原始URL
     * @return 短码
     */
    public String createShortUrl(String originalUrl) {
        String threadName = Thread.currentThread().getName();
        log.info("[{}] 开始创建短链接: {}", threadName, originalUrl);

        // 1. 检查是否已存在该URL的短链接
        String existingCode = getShortCodeByUrl(originalUrl);
        if (existingCode != null) {
            log.info("[{}] URL已存在短链接: {} -> {}", threadName, originalUrl, existingCode);
            return existingCode;
        }
        log.info("[{}] URL不存在，开始创建新短链接", threadName);

        // 2. 用Redis INCR原子生成唯一ID
        Long id = redisTemplate.opsForValue().increment(ID_KEY);
        log.info("[{}] Redis INCR 生成唯一ID: {}", threadName, id);

        // 3. 用ID生成短码
        String shortCode = base62Encode(id);
        log.info("[{}] Base62编码: {} -> {}", threadName, id, shortCode);

        // 4. 检查短码是否已存在（双重检查）
        String existingByCode = getShortCodeByCode(shortCode);
        if (existingByCode != null) {
            log.error("[{}] 短码已存在！shortCode={}, existingUrl={}", threadName, shortCode, existingByCode);
            throw new RuntimeException("短码冲突: " + shortCode);
        }

        // 5. 一次性插入数据库（ID和短码都有了）
        ShortUrl shortUrl = new ShortUrl();
        shortUrl.setId(id);
        shortUrl.setShortCode(shortCode);
        shortUrl.setOriginalUrl(originalUrl);
        shortUrl.setClickCount(0L);

        log.info("[{}] 准备插入数据库: id={}, shortCode={}, originalUrl={}", threadName, id, shortCode, originalUrl);
        shortUrlMapper.insert(shortUrl);
        log.info("[{}] 插入数据库成功", threadName);

        // 6. 缓存到Redis
        String cacheKey = "short:url:" + shortCode;
        redisTemplate.opsForValue().set(cacheKey, originalUrl, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);

        log.info("[{}] 创建短链接完成: {} -> {}", threadName, shortCode, originalUrl);
        return shortCode;
    }

    /**
     * 根据短码获取原始URL
     * @param shortCode 短码
     * @return 原始URL，不存在返回null
     */
    public String getOriginalUrl(String shortCode) {
        // 1. 先查Redis缓存
        String cacheKey = "short:url:" + shortCode;
        String cachedUrl = redisTemplate.opsForValue().get(cacheKey);

        if (cachedUrl != null) {
            log.info("缓存命中: {} -> {}", shortCode, cachedUrl);
            // 更新点击次数（异步）
            updateClickCount(shortCode);
            return cachedUrl;
        }

        // 2. 缓存未命中，查数据库
        LambdaQueryWrapper<ShortUrl> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ShortUrl::getShortCode, shortCode)
               .eq(ShortUrl::getDeleted, 0);
        ShortUrl shortUrl = shortUrlMapper.selectOne(wrapper);

        if (shortUrl == null) {
            log.warn("短链接不存在: {}", shortCode);
            return null;
        }

        // 3. 回填Redis缓存
        redisTemplate.opsForValue().set(cacheKey, shortUrl.getOriginalUrl(), CACHE_EXPIRE_HOURS, TimeUnit.HOURS);

        // 4. 更新点击次数（异步）
        updateClickCount(shortCode);

        log.info("数据库查询成功: {} -> {}", shortCode, shortUrl.getOriginalUrl());
        return shortUrl.getOriginalUrl();
    }

    /**
     * 根据原始URL获取短码
     * @param originalUrl 原始URL
     * @return 短码，不存在返回null
     */
    private String getShortCodeByUrl(String originalUrl) {
        LambdaQueryWrapper<ShortUrl> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ShortUrl::getOriginalUrl, originalUrl)
               .eq(ShortUrl::getDeleted, 0);
        ShortUrl shortUrl = shortUrlMapper.selectOne(wrapper);
        return shortUrl != null ? shortUrl.getShortCode() : null;
    }

    /**
     * 根据短码获取原始URL（检查短码是否已存在）
     * @param shortCode 短码
     * @return 原始URL，不存在返回null
     */
    private String getShortCodeByCode(String shortCode) {
        LambdaQueryWrapper<ShortUrl> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ShortUrl::getShortCode, shortCode)
               .eq(ShortUrl::getDeleted, 0);
        ShortUrl shortUrl = shortUrlMapper.selectOne(wrapper);
        return shortUrl != null ? shortUrl.getOriginalUrl() : null;
    }

    /**
     * Base62编码
     * @param num 数字
     * @return 编码后的字符串
     */
    private String base62Encode(long num) {
        StringBuilder sb = new StringBuilder();
        while (num > 0) {
            sb.append(BASE62_CHARS.charAt((int) (num % 62)));
            num /= 62;
        }
        // 先反转，再填充前导零
        String result = sb.reverse().toString();
        while (result.length() < 6) {
            result = "0" + result;
        }
        return result;
    }

    /**
     * 更新点击次数（简化版，实际应用中应异步处理）
     * @param shortCode 短码
     */
    private void updateClickCount(String shortCode) {
        // 简化处理：直接更新数据库
        // 实际应用中应该用Redis原子递增 + 定时同步到数据库
        ShortUrl shortUrl = new ShortUrl();
        LambdaQueryWrapper<ShortUrl> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ShortUrl::getShortCode, shortCode);
        // 这里简化处理，实际应该用INCR
    }
}
