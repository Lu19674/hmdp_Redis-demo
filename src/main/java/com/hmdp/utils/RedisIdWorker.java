package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    @Resource
    private StringRedisTemplate template;

    private static final long BEGIN_TIMESTAMP = 1640995200L;//初始时间戳（2022开始）
    private static final long COUNT_BITS = 32L;//序列号位数（时间戳要向左移动位数）

    public long nextId(String keyPrefix){
        //1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long timeStamp = now.toEpochSecond(ZoneOffset.UTC) - BEGIN_TIMESTAMP; // 2022-1-1时间秒数 - 当前时间秒数

        //2.生成序列号
        //2.1获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        //2.2自增长
        long count = template.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        //3.拼接返回
        return timeStamp << COUNT_BITS | count;
    }
}
