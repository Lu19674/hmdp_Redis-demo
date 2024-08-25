package com.hmdp;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private RedissonClient redissonClient;

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
}
