package com.shorturl.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shorturl.entity.ShortUrl;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ShortUrlMapper extends BaseMapper<ShortUrl> {

    @Select("SELECT MAX(id) FROM short_url WHERE deleted = 0")
    Long selectMaxId();
}
