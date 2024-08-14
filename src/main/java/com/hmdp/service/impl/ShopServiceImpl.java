package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate template;

    /**
     * 根据id查询店铺
     * @param id
     * @return
     */
    @Override
    public Result queryShopById(Long id) {
        String key = CACHE_SHOP_KEY +id;
        String shopJson = template.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            //存在
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        Shop shop = getById(id);
        if(BeanUtil.isEmpty(shop)){
            //数据库没查到
            return Result.fail("店铺不存在！");
        }
        String cacheShop = JSONUtil.toJsonStr(shop);
        template.opsForValue().set(CACHE_SHOP_KEY+id,cacheShop,CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(shop);

    }

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
        template.delete(CACHE_SHOP_KEY +id);
        return Result.ok();
    }
}
