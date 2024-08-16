package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.RedisData;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import javax.annotation.Resources;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate template;
    @Autowired
    private CacheClient cacheClient;

    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 根据id查询店铺
     *
     * @param id
     * @return
     */
    @Override
    public Result queryShopById(Long id) {
        //存入 null 进 Redis 解决缓存穿透
//        Shop shop = cacheClient.queryWithPassThrough(id,CACHE_SHOP_KEY,Shop.class, this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);

        //添加互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);

        //互斥锁+逻辑过期时间，解决缓存击穿
        Shop shop = cacheClient.queryWithLogicalExpire(id,CACHE_SHOP_KEY,Shop.class,this::getById,10L,TimeUnit.SECONDS);

        return shop != null ? Result.ok(shop) : Result.fail("店铺不存在！");
    }

    //存null进Redis，解决缓存穿透
    private Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;
        String shopJson = template.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)) {
            // 缓存中存在，将数据返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断是否从 Redis 获取出的是否是空值 “”
        if (Objects.equals(shopJson, "")) {
            //是空值说明有缓存穿透现象，返回错误信息
            return null;
        }
        Shop shop = getById(id);
        if (BeanUtil.isEmpty(shop)) {
            //数据库没查到
            //将空值写入 Redis 防止缓存穿透
            template.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        String cacheShop = JSONUtil.toJsonStr(shop);
        template.opsForValue().set(CACHE_SHOP_KEY + id, cacheShop, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    //添加互斥锁，解决缓存击穿
    private Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        String shopJson = template.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)) {
            // 缓存中存在，将数据返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断是否从 Redis 获取出的是否是空值 “”
        if (Objects.equals(shopJson, "")) {
            //是空值说明有缓存穿透现象，返回错误信息
            return null;
        }
        String lockKey = null;
        Shop shop = null;
        try {
            //尝试获取锁
            lockKey = LOCK_SHOP_KEY + id;
            boolean tryLock = tryLock(lockKey);
            if (!tryLock) {
                //没获取到锁，休眠50ms，重新查Redis、获取锁
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //获取到了锁，再检查Redis中是否已有缓存，后再决定是否查数据库，更新Redis
            shopJson = template.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJson)) {
                return JSONUtil.toBean(shopJson, Shop.class);// 缓存中存在，将数据返回，无需重建缓存
            }
            if (Objects.equals(shopJson, "")) {
                return null;
            }
            shop = getById(id);//查库
            if (BeanUtil.isEmpty(shop)) {
                //数据库没查到
                //将空值写入 Redis 防止缓存穿透
                template.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            String cacheShop = JSONUtil.toJsonStr(shop);
            template.opsForValue().set(CACHE_SHOP_KEY + id, cacheShop, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException();
        } finally {
            //释放锁
            unlock(lockKey);
        }
        return shop;
    }

    //添加逻辑过期时间，解决缓存击穿
    private Shop queryWithLogicalExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;
        String shopJson = template.opsForValue().get(key);
        if (StrUtil.isBlank(shopJson)) {
            // 缓存中不存在，返回null
            return null;
        }
        RedisData shopRedisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject date = (JSONObject) shopRedisData.getData();
        Shop shop = JSONUtil.toBean(date, Shop.class);//店铺信息
        LocalDateTime expireTime = shopRedisData.getExpireTime();//过期时间
        //判断缓存是否过期（expireTime属性）
        if (expireTime.isAfter(LocalDateTime.now())) {//比较是否是当前时间之后
            //未过期，返商铺信息
            return shop;
        }
        //尝试获取锁
        String lockKey;
        lockKey = LOCK_SHOP_KEY + id;
        boolean tryLock = tryLock(lockKey);
        if (!tryLock) {
            //没获取到锁，不用等待，直接返回店铺信息（旧）
            return shop;
        }
        //获取到了锁，再检查Redis中信息是否没过期，后再决定是否开启独立线程（让线程完成缓存重建、释放锁），返回店铺信息
        shopJson = template.opsForValue().get(key);
        if (Objects.equals(shopJson, "")) {
            return null;
        }
        shopRedisData = JSONUtil.toBean(shopJson, RedisData.class);
        if (shopRedisData.getExpireTime().isAfter(LocalDateTime.now())) {
            // 缓存中的店铺信息还没过期，将数据返回，无需重建缓存
            return JSONUtil.toBean((JSONObject) shopRedisData.getData(), Shop.class);
        }
        //开启独立线程，完成缓存重建、释放锁
        CACHE_REBUILD_EXECUTOR.submit(()->{
            try {
                saveShop2Redis(id,CACHE_SHOP_TTL);
            } catch (Exception e) {
                throw new RuntimeException();
            }finally{
                unlock(lockKey);
            }
        });
        return shop;
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

    //存储包含逻辑过期时间的店铺数据到 Redis 以解决 热点key 的缓存击穿问题
    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        //查库
        log.info("查库id={}缓存重建",id);
        Shop shop = getById(id);
        Thread.sleep(100);
        //封装过期时间成 RedisData
        RedisData redisData = RedisData.builder()
                .expireTime(LocalDateTime.now().plusSeconds(expireSeconds))
                .data(shop)
                .build();
        //存入Redis
        template.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }



    /**
     * 修改店铺数据
     *
     * @param shop
     * @return
     */
    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        //1.更新数据库
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        updateById(shop);
        //2.删除缓存
        template.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
