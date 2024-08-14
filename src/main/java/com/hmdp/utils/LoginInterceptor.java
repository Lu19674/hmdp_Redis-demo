package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
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
public class LoginInterceptor implements HandlerInterceptor {

    @Override//发送请求前拦截
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.判断是否需要拦截（根据ThreadLocal中是否有用户信息）
        UserDTO user = UserHolder.getUser();
        if (BeanUtil.isEmpty(user)) {
            log.info("用户未登录，拦截！");
            response.setStatus(401);
            return false;
        }
        //2.有用户，放行
        log.info("放行。。");
        return true;
    }
}

