package com.shorturl.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("short_url")
public class ShortUrl {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String shortCode;

    private String originalUrl;

    private LocalDateTime createTime;

    private Long clickCount;

    @TableLogic
    private Integer deleted;
}
