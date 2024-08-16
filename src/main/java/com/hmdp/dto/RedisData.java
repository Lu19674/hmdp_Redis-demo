package com.hmdp.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;


@Data
@Builder
public class RedisData {
    //逻辑过期时间
    private LocalDateTime expireTime;

    //封装的数据
    private Object data;
}
