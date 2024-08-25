package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfig {
    @Bean
    public RedissonClient redissonClient(){
        //创建配置类
        Config config = new Config();
        //添加 redis 地址，这里是单点地址，也可用config.useClusterServers()添加集群地址
        config.useSingleServer().setAddress("redis://192.168.88.130:6379").setPassword("195277");
        //创建客户端
        return Redisson.create(config);
    }
}
