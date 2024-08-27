package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private RedissonClient redissonClient;
    @Resource
    private StringRedisTemplate template;
    @Resource
    private IShopService shopService;

    @Test
    void redissonTest(){
        //获取锁（可重入），指定锁的名称
        RLock anyLock = redissonClient.getLock("anyLock");
        //尝试获取锁，参数：（ 获取锁的最大等待时间（重试间隔），锁自动释放时间，时间单位 ）
        try {
            boolean isLock = anyLock.tryLock(1, 10, TimeUnit.SECONDS);
            if(isLock){
                System.out.println("执行业务");
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }finally{
            //释放锁
            anyLock.unlock();
        }
    }

    /**
     * 店铺地理坐标，根据店铺类型分类存入Redis
     */
    @Test
    void loadShopData(){
        //1，查询店铺信息
        List<Shop> shopList = shopService.list();
        //2.把店铺按 typeId 分组
        Map<Long, List<Shop>> maps = shopList.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //3.分批完成写入Redis
        for (Map.Entry<Long, List<Shop>> entry : maps.entrySet()) {
            //获取店铺类型id
            Long typeId = entry.getKey();
            //此店铺类型对应 Redis 的key
            String key = SHOP_GEO_KEY + typeId;
            //获取此类型的店铺集合
            List<Shop> shops = entry.getValue();
            //每个类型的 shops 集合的（店铺id：经纬度）集，封装成 GeoLocation 集合实现批量写入
            ArrayList<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(shops.size());
            shops.forEach(shop->{
                //逐个封装成GeoLocation加入locations集合
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(), //店铺id
                        new Point(shop.getX(),shop.getY())//经纬度
                ));
            });
            //4.每批 locations 存的（店铺id：经纬度）写入Redis
            template.opsForGeo().add(key,locations);
        }
    }
    @Test //UV统计测试
    void testHyperLogLog(){
        String[] values = new String[1000];
        int j=0;
        for (int i = 0; i < 1000000; i++) {
            j=i%1000;
            values[j]="user_"+i;
            if(j==999){
                //发送到Redis
                template.opsForHyperLogLog().add("hlUV",values);
            }
        }
        //统计数量
        Long hlUVCount = template.opsForHyperLogLog().size("hlUV");
        System.out.println(hlUVCount);
    }
}
