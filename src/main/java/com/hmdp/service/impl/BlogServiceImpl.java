package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.stream.CollectorUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.ScrollResult;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

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
    @Resource
    private IFollowService followService;


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
        // 查询笔记作者信息，及用户是否点赞过
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

    /**
     * 新增笔记
     * @param blog
     * @return
     */
    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        //如果未选择关联的店铺，返回提示信息
        if(blog.getShopId() == null){
            return Result.fail("请关联店铺~");
        }
        // 保存探店博文
        boolean isSuccess = save(blog);
        if(!isSuccess){
            return Result.fail("保存笔记不成功！");
        }
        // 查询此用户所有粉丝（把他关注的人查出来）
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        // 推送笔记id给所有粉丝的 ZSet 收件箱
        for (Follow follow : follows) {
            Long followUserId = follow.getUserId();//每位粉丝id
            String key=FEED_KEY+followUserId;//每位粉丝对应的 ZSet 收件箱的 key
            //添加进收件箱
            template.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());//用时间戳排序
        }
        // 返回笔记id
        return Result.ok(blog.getId());
    }

    /**
     * 滚动分页查询 收件箱里关注人的发布笔记
     * @param max 最大时间戳
     * @param offset 偏移量
     * @return
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.获取当前用户
        Long userId =UserHolder.getUser().getId();
        //2.查询收件箱 ZREVRANGEBYSCORE Key Max Min LIMIT offset count
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = template.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset /*偏移量*/, 2 /*每页查询条数*/);
        //3.非空判断
        if (CollectionUtil.isEmpty(typedTuples)) {
            return Result.ok();
        }
        //4.解析数据：blogId、minTime(时间戳)、offset
        ArrayList<Object> blogIds = new ArrayList<>(typedTuples.size());//收集 blogIds
        long minTime = 0;//记录最小时间戳
        int count = 1;//记录偏移量
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            Long blogId = Long.valueOf(tuple.getValue());
            blogIds.add(blogId);
            Long time = tuple.getScore().longValue();
            if(time==minTime){
                count++;
            }else{
                minTime=time;
                count=1;//重置为1：记录后缀的与最小时间戳一致（后缀相同）的个数（偏移量）
            }
        }
        offset=count;
        String idStr = StrUtil.join(",", blogIds);//ids集合元素用“，”拼接成字符串
        //5.排序批量查询笔记
        List<Blog> blogs = query()
                .in("id",blogIds)//in 查出的数据，不能自动排序
                .last("order by field(id,"+idStr+")") //手动写最后一条sql，使其根据id字段排序
                .list();
        //6.查询笔记作者信息，及用户是否点赞过
        blogs.forEach(blog ->{
            Long id = blog.getUserId();
            User user = userService.getById(id);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            isBlogLiked(blog);
        });
        //7.封装 ScrollResult 滚动分页结果返回
        ScrollResult scrollResult = ScrollResult.builder()
                .list(blogs)
                .minTime(minTime)
                .offset(offset)
                .build();
        return Result.ok(scrollResult);
    }
}
