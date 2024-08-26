package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.stream.CollectorUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate template;

    /**
     * 批量查询达人探店日志
     * @param current
     * @return
     */
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            Long userId = blog.getUserId();
            User user = userService.getById(userId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            isBlogLiked(blog);
        });
        return Result.ok(records);


    }

    /**
     * id查询达人探店日志
     * @param id
     * @return
     */
    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if(BeanUtil.isEmpty(blog)){
            return Result.fail("笔记不存在！");
        }
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
        //查看此用户是否点过赞，是就赋值 blog的属性 isLike = true
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    /**
     * 查看此用户是否对此笔记点过赞
     * @param blog
     */
    private void isBlogLiked(Blog blog) {
        //1.查询登录用户
        UserDTO user = UserHolder.getUser();
        if(BeanUtil.isEmpty(user)){
            //用户未登录，无需查询是否点过赞
            return;
        }
        String userId = String.valueOf(user.getId());
        //2.判断当前登录用户是否已经点赞
        String key =BLOG_LIKED_KEY +blog.getId();
        Double isMember = template.opsForZSet().score(key, userId);
        blog.setIsLike(isMember != null);
    }

    /**
     * 点赞
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        //1.查询登录用户
        String userId = String.valueOf(UserHolder.getUser().getId());
        //2.判断当前登录用户是否已经点赞
        String key =BLOG_LIKED_KEY +id;
        Double isMember = template.opsForZSet().score(key, userId);
        if(isMember == null){
            //没有点过赞，更新笔记的点赞数（+1），把点赞人id存入笔记对应的key的set集合里
            boolean isSuccess = update().setSql("liked = liked + 1")
                    .eq("id", id).update();
            if(!isSuccess) return Result.fail("点赞失败！");
            template.opsForZSet().add(key, userId,System.currentTimeMillis()); //用时间戳来排序
        }else {
            //已点赞，取消点赞（-1），把set中对应的点赞人id删除
            boolean isSuccess = update().setSql("liked = liked -1")
                    .eq("id", id).update();
            if(!isSuccess) return Result.fail("取消点赞失败！");
            template.opsForZSet().remove(key,userId);
        }

        return Result.ok();
    }

    /**
     * 查询笔记点赞top5用户
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLikes(Long id) {
        String key =BLOG_LIKED_KEY +id;
        //ZSet里查top5的用户id集合
        Set<String> setIds = template.opsForZSet().range(key, 0, 4);
        if(setIds.isEmpty()) return Result.ok(Collections.emptyList()); //没人点赞，返回空集合
        List<Long> ids = setIds.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        //根据ids查库，获取用户集信息 （where id in(4,6,3,2) order by field(id,4,,6,3,2)）
        List<User> users = userService.query()
                .in("id",ids)//in 查出的数据，不能自动排序
                .last("order by field(id,"+idStr+")") //手动写最后一条sql，使其根据id字段排序
                .list();
        //封装 UsetDTO 集合返回
        List<UserDTO> userDTOS = users.stream()
                .map(u -> BeanUtil.copyProperties(u, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}
