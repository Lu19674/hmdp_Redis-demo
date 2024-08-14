package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate template;

    /**
     * 查询店铺类型信息
     * @return
     */
    @Override
    public Result queryTypeList() {
        //查缓存
        Set<String> shopTypes = template.opsForSet().members(CACHE_SHOP_TYPE_KEY);
        List<ShopType> shopTypeList = new ArrayList<>();
        if(CollectionUtil.isNotEmpty(shopTypes)){
            List<ShopType> finalShopTypeList = shopTypeList; //lambda中不许有非final的变量
            shopTypes.forEach(s->{ // 缓存中取出的是JSON字符串，用JSONUtil工具类变为bean，再放进集合返回
                ShopType shopType = JSONUtil.toBean(s, ShopType.class);
                finalShopTypeList.add(shopType);
            });
            return Result.ok(shopTypeList);
        }
        //缓存不存在，查数据库
        shopTypeList = list();
        if(CollectionUtil.isEmpty(shopTypeList)){
            return Result.fail("店铺类型为空！");
        }
        shopTypeList.forEach(s->{// 把bean转为JSON字符串，再逐个放进Redis
            String shopTypeJsonStr = JSONUtil.toJsonStr(s);
            template.opsForSet().add(CACHE_SHOP_TYPE_KEY,shopTypeJsonStr);
        });
        template.expire(CACHE_SHOP_TYPE_KEY,CACHE_SHOP_TYPE_TTL, TimeUnit.HOURS);
        return Result.ok(shopTypeList);
    }
}
