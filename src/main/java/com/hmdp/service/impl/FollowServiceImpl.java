package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private HttpServletResponse response;
    @Resource
    private StringRedisTemplate template;
    @Resource
    private IUserService userService;


    /**
     * 关注或取关
     * @param followUserId
     * @param isFollow
     * @return
     */
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        Long userId=UserHolder.getUser().getId();
        String key= "follows:"+userId;
        //判断是关注还是取关
        if(isFollow){
            //关注，新增关注表信息
            Follow follow = Follow.builder()
                    .userId(userId)
                    .followUserId(followUserId)
                    .build();
            boolean isSuccess = save(follow);

            if(isSuccess){
                log.info("关注成功！");
                //新增此用户的关注信息到Redis
                template.opsForSet().add(key, followUserId.toString());
            }else log.info("关注失败！");
        }else {
            //取关，查询删除
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followUserId));
            if(isSuccess){
                log.info("取关成功！");
                //删除Redis中的此用户的关注信息
                template.opsForSet().remove(key,followUserId.toString());
            }else log.info("取关失败！");
        }
        return Result.ok();
    }

    /**
     * 查看是否关注
     * @param followUserId
     * @return
     */
    @Override
    public Result isFollow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        Long isFollow = query()
                .eq("user_id", userId)
                .eq("follow_user_id", followUserId)
                .count();
        return Result.ok(isFollow>0);
    }

    @Override
    public Result followCommons(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        //到Redis中查询共同关注（求两key的交集）
        Set<String> intersect = template.opsForSet().intersect("follows:" + userId, "follows:" + followUserId);
        if(CollectionUtil.isEmpty(intersect)){
            //没有共同关注好友，返回空集合
            return Result.ok(Collections.emptyList());
        }
        //解析共同关注对象的id
        List<Long> ids = intersect.stream()
                .map(Long::valueOf)
                .collect(Collectors.toList());

        //查询共同关注的用户信息（封装进UserDTO返回）
        List<User> users = userService.listByIds(ids);
        List<UserDTO> userDTOS = users.stream()
                .map(u -> BeanUtil.copyProperties(u, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}
