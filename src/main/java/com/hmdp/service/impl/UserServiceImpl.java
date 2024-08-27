package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.NumberUtil;
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
import com.hmdp.utils.UserHolder;
import io.netty.util.internal.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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
        if (RegexUtils.isPhoneInvalid(phone)) {
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
        if (RegexUtils.isPhoneInvalid(phone)) {
            //手机号不符合，删除 code ， 返回错误信息
            template.delete(LOGIN_CODE_KEY + phone);
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
        user = lambdaQuery()
                .eq(User::getPhone, phone)
                .one();
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

    /**
     * 用户签到
     * @return
     */
    @Override
    public Result sign() {
        //1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2.获取当前日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key（月份+用户id）
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth(); // 此获取到的是 1-31 ，Redis的BitMap数据是0-30，因此写入时要减一
        //5.写入Redis （SETBIT key offset 1）
        template.opsForValue().setBit(key,dayOfMonth-1,true);
        return Result.ok();

    }

    /**
     * 统计截至用户连续签到天数
     * @return
     */
    @Override
    public Result signCount() {
        //1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2.获取当前日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key（月份+用户id）
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //5.获取本月截至今天为止的所有签到记录，返回的是一个十进制数据
        List<Long> result = template.opsForValue().bitField( //BITFIELD sign:{userId}:{keySuffix} GET u{dayOfMonth} 0
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if(CollectionUtil.isEmpty(result))
            //没有任何签到结果
            return Result.ok(0);
        Long num = result.get(0);
        if(num==null||num==0)
            return Result.ok(0);
        //6.循环遍历
        int count;//统计num数字从右往左有多少个“1”bit位（连续签到多少天）
        if((num&1)==0) count =0;//今天没签到的计数器初始值
        else count=1;//今天签到了的计数器初始值
        num>>>=1;//先把数字右移一位，排除掉今天，从昨天向前开始统计
        while(true){
            //6.1让数字与1做 与 运算 ，得到数字的最后一个bit位 //再判断这个bit位是否为0
            if((num&1)==0)
                //如果为0，则未签到，结束
                break;
            else
                //为1，已签到，计数+1
                count++;
            //把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num>>>=1; //或 ： num/=2;
        }
        return Result.ok(count);
    }
}
