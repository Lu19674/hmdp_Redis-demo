package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import io.netty.util.internal.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

@Slf4j
public class RefreshTokenInterceptor implements HandlerInterceptor {
    private StringRedisTemplate template;
    public RefreshTokenInterceptor(StringRedisTemplate template) {
        this.template = template;
    }
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.从请求头中获取
        String token = request.getHeader("authorization");// authorization是前端存在请求头中的数据命名
        if (StringUtil.isNullOrEmpty(token)) {
            return true; //放行，让 LoginInterceptor 登录校验拦截器 拦截
        }
        //2.基于 token 再从 Redis 中获取用户
        String key = LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = template.opsForHash().entries(key);
        //3.判断用户是否存在
        if (userMap.isEmpty()) {
            return true; //放行，让 LoginInterceptor 登录校验拦截器 拦截
        }
        //4.存在，保存用户信息到ThreadLocal
        //4.1将 Redis 获取出的 userMap 转为 user对象
        UserDTO user = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        //4.2存入ThreadLocal
        UserHolder.saveUser(user);
        //5.刷新 token 有效期
        template.expire(key, LOGIN_USER_TTL, TimeUnit.MINUTES);
        //6.放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();//视图渲染完毕，释放ThreadLocal变量中的user信息

    }
}
