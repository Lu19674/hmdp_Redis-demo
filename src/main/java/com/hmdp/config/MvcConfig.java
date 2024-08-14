package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Resource
    private StringRedisTemplate template;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // ThreadLocal的user信息（token）刷新拦截器
        registry.addInterceptor(new RefreshTokenInterceptor(template))//用到template获取token
                .addPathPatterns("/**") // 全都拦截
                .order(0);//拦截器执行顺序优先级（0最高）

        // 检验登陆状态拦截器
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns( // 过滤掉拦截的路径
                        "/user/login",
                        "/user/code",
                        "/shop/**",
                        "/voucher/**",
                        "shop-type/**",
                        "/upload/**",
                        "/blog/hot"
                )
                .order(1);
    }

}
