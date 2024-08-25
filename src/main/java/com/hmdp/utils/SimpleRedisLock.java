package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {
    private String name;
    private StringRedisTemplate template;

    public SimpleRedisLock(String name, StringRedisTemplate template) {
        this.name = name;
        this.template = template;
    }

    private static final String KEY_PREFIX = "lock:";// key值前缀
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";//value值线程id前缀
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;//用来读取脚本文件对象的变量
    static {
        UNLOCK_SCRIPT=new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unLock.lua"));//脚本路径
        UNLOCK_SCRIPT.setResultType(Long.class);//返回值类型
    }

    //尝试获取锁
    @Override
    public boolean tryLock(Long timeoutSec) {
        //获取当前线程id+UUID作为value（线程标识）
        String ThreadId = ID_PREFIX + Thread.currentThread().getId();
        Boolean success = template.opsForValue().setIfAbsent(KEY_PREFIX + name, ThreadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);//避免 success 为 null 时自动拆箱报异常
    }
    //释放锁
    public void unLockOfLua(){
        template.execute(//调用脚本
                UNLOCK_SCRIPT,//脚本对象
                Collections.singletonList(KEY_PREFIX + name),//用集合工具类把 key 值一个元素放进集合传参
                ID_PREFIX + Thread.currentThread().getId()//线程标识
        );
    }
    @Override
    public void unLock() {
        //获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取要释放的锁的标识
        String lockId = template.opsForValue().get(KEY_PREFIX + name);
        //判断锁是否是当前线程的锁标识
        if (threadId.equals(lockId)) {
            //是，才释放，防止误释放产生并发问题
            template.delete(KEY_PREFIX + name);
        }
        //不是就不用管
    }
}
