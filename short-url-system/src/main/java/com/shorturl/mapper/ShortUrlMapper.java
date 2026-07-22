package com.shorturl.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shorturl.entity.ShortUrl;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ShortUrlMapper extends BaseMapper<ShortUrl> {
}
