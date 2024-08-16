package com.hmdp.service.impl;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest
class ShopServiceImplTest {

    @Autowired
    private CacheClient cacheClient;
    @Autowired
    private ShopServiceImpl shopService;

    @Test
    public void saveShop2RedisTest() throws InterruptedException {
//        shopService.saveShop2Redis(1L,30L);
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY+shop.getId(),shop,10L, TimeUnit.SECONDS);
    }
}