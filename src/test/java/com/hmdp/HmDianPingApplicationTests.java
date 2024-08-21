package com.hmdp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@EnableAspectJAutoProxy(exposeProxy = true)
@SpringBootTest
@MapperScan("com.hmdp.mapper")
class HmDianPingApplicationTests {


}
