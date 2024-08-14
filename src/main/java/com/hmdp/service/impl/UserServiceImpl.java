package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import io.netty.util.internal.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate template;
    @Autowired
    private UserMapper userMapper;

    @Autowired
    private HttpServletRequest Request;

    /**
     * 发送手机验证码
     *
     * @param phone
     * @param session
     * @return
     */
    @Override//发送手机验证码
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if (!RegexUtils.isPhoneInvalid(phone)) {
            //不符合，返回错误信息
            return Result.fail("手机格式错误！请重新输入");
        }
        //2.生成验证码
        String code = RandomUtil.randomNumbers(6);
        //3.保存验证码到Redis
        template.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //4.发送验证码（需调用短信服务接口，比较麻烦，跳过）
        log.debug("发送短信验证码成功，验证码：{}", code);

        return Result.ok();

    }

    /**
     * 用户登录
     * @param loginForm
     * @param session
     * @return
     */
    @Override//用户登录
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号和验证码
        String phone = loginForm.getPhone();
        String cacheCode = template.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (StringUtil.isNullOrEmpty(cacheCode)) {
            return Result.fail("无效验证码，过期或有误。");
        }
        if (!RegexUtils.isPhoneInvalid(phone)) {
            //手机号不符合，返回错误信息
            return Result.fail("手机格式错误！请重新输入");
        } else if (!loginForm.getCode().equals(cacheCode)) {
            //验证码错误，返回错误信息
            return Result.fail("验证码有误，请重新发送！");
        }
        //2.验证码通过，将 Redis 中缓存的 code 删除
        template.delete(LOGIN_CODE_KEY + phone);
        //3.根据手机号查询用户（mp实现）
        User user = lambdaQuery()
                .eq(User::getPhone, phone)
                .one();

        //4.判断用户是否存在
        if (user == null) {
            //不存在，创建新用户（自动注册），保存用户到数据库
            user = User.builder()
                    .phone(phone)
                    .createTime(LocalDateTime.now())
                    .updateTime(LocalDateTime.now())
                    .nickName(USER_NICK_NAME_PREFIX + RandomUtil.randomNumbers(10))
                    .build();
            save(user);// mp 插入一条数据到库中
        }

        //5.保存用户到Redis
        //5.1用 UUID 随机生成 key （token）
        String token = UUID.randomUUID().toString(true);
        //5.2把 userDTO 转为 HashMap （键值都转为String）
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class); //对象拷贝成 UserDTO
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                    .setIgnoreNullValue(true)
                    .setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString()));
        //5.3存储入Redis
        String key = LOGIN_USER_KEY + token;
        template.opsForHash().putAll(key, userMap);
        template.expire(key, LOGIN_USER_TTL, TimeUnit.MINUTES); //给指定 key 设置过期时间 （token有效期30分钟）

        //6.返回token给前端
        log.info("返回token：{}",token);
        return Result.ok(token);
    }

    /**
     * 用户等出
     * @return
     */
    @Override
    public Result logout() {
        String token = Request.getHeader("authorization");
        if (!StrUtil.isBlank(token)) {
            template.delete(LOGIN_USER_KEY + token); //删除Redis中user对应的token值
        }
        return Result.ok();
    }
}
