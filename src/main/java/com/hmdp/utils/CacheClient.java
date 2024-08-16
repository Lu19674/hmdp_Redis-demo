package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

@Component
@Slf4j
public class CacheClient {
    @Resource
    private StringRedisTemplate template;

    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 存值
     * @param key 键
     * @param value 值对象
     * @param time 过期时间
     * @param unit 过期时间单位
     */
    public void set(String key, Object value, Long time, TimeUnit unit){
        template.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    /**
     * 存逻辑过期时间的值
     * @param key 建
     * @param value 值对象
     * @param time 过期时间
     * @param unit 过期时间单位
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData=RedisData.builder()
                .data(value)
                .expireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)))
                .build();
        template.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 用存空值解决缓存穿透问题的 根据id查询方法
     * @param id id
     * @param keyPrefix 缓存key前缀
     * @param type 返回类型
     * @param dbFallback 查询逻辑
     * @param time 缓存过期时间
     * @param unit 过期时间单位
     * @param <R> 返回值类型
     * @param <ID> id类型
     * @return 查询结果
     */
    public <R,ID> R queryWithPassThrough(ID id, String keyPrefix, Class<R> type,
                                          Function<ID,R> dbFallback,Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = template.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            // 缓存中存在，将数据返回
            return JSONUtil.toBean(json, type);
        }
        //判断是否从 Redis 获取出的是否是空值 “”
        if (Objects.equals(json, "")) {
            //是空值说明有缓存穿透现象，返回错误信息
            return null;
        }
        R resData = dbFallback.apply(id);
        if (BeanUtil.isEmpty(resData)) {
            //数据库没查到
            //将空值写入 Redis 防止缓存穿透
            template.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //写入缓存
        this.set(key,resData,time,unit);
        return resData;
    }

    /**
     * 互斥锁+逻辑过期时间，解决缓存击穿
     * @param id id
     * @param keyPrefix key前缀
     * @param type 返回类型
     * @param dbFallback 查询逻辑
     * @param time 逻辑过期时间
     * @param unit 过期时间单位
     * @param <R> 返回类型
     * @param <ID> id类型
     * @return 查询结果
     */
    public <R,ID> R queryWithLogicalExpire(ID id, String keyPrefix, Class<R> type,
                                           Function<ID,R> dbFallback,Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = template.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            // 缓存中不存在，返回null
            return null;
        }
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject date = (JSONObject) redisData.getData();
        R res = JSONUtil.toBean(date, type);//店铺信息
        LocalDateTime expireTime = redisData.getExpireTime();//过期时间
        //判断缓存是否过期（expireTime属性）
        if (expireTime.isAfter(LocalDateTime.now())) {//比较是否是当前时间之后
            //未过期，返商铺信息
            return res;
        }
        //尝试获取锁
        String lockKey;
        lockKey = LOCK_SHOP_KEY + id;
        boolean tryLock = tryLock(lockKey);
        if (!tryLock) {
            //没获取到锁，不用等待，直接返回店铺信息（旧）
            return res;
        }
        //获取到了锁，再检查Redis中信息是否没过期，后再决定是否开启独立线程（让线程完成缓存重建、释放锁），返回店铺信息
        json = template.opsForValue().get(key);
        if (Objects.equals(json, "")) {
            return null;
        }
        redisData = JSONUtil.toBean(json, RedisData.class);
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            // 缓存中的店铺信息还没过期，将数据返回，无需重建缓存
            return JSONUtil.toBean((JSONObject) redisData.getData(), type);
        }
        //开启独立线程，完成缓存重建、释放锁
        CACHE_REBUILD_EXECUTOR.submit(()->{
            try {
                R r=dbFallback.apply(id);
                //重建缓存
                this.setWithLogicalExpire(key,r,time,unit);
            } catch (Exception e) {
                throw new RuntimeException();
            }finally{
                unlock(lockKey);
            }
        });
        return res;
    }


    //尝试获取互斥锁
    private boolean tryLock(String key) {
        //setIfAbsent() --"如果值缺席就添加"
        Boolean flag = template.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    //释互斥放锁
    private void unlock(String key) {
        template.delete(key);
    }

}
