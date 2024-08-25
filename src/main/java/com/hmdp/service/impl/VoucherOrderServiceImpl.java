package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private StringRedisTemplate template;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker idWorker;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private RedisIdWorker redisIdWorker;

    //代理对象（定义成全局变量是为了创建订单的子线程能调用到，子线程拿不到主线的代理对象）
    private IVoucherOrderService proxy;
    /**
     * 优惠券下单
     * @param voucherId
     * @return
     */
    @Override //(基于stream消息队列)
    public Result seckillVoucher(Long voucherId) {
        //执行 lua 脚本判断是否库存不足和重复下单
        Long userId = UserHolder.getUser().getId();//获取用户
        long orderId = redisIdWorker.nextId("order");//生成全局唯一订单id
        Long res = template.execute(
                SECKILL_SCRIPT,//脚本对象
                Collections.emptyList(), //KEYS[]（空集合）
                voucherId.toString(), userId.toString(),String.valueOf(orderId) //ARGV[]
        );
        int r= Objects.requireNonNull(res).intValue(); //lua脚本执行结果
        if(r!=0){
            return Result.fail(r==1?"已被抢光！来晚了！":"不能二次下单");
        }

        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回订单id
        return Result.ok(orderId);
    }

   /* @Override //（基于阻塞队列）
    public Result seckillVoucher(Long voucherId) {
        //执行 lua 脚本判断是否库存不足和重复下单
        Long userId = UserHolder.getUser().getId();
        Long res = template.execute(
                SECKILL_SCRIPT,//脚本对象
                Collections.emptyList(), //KEYS[]（空集合）
                voucherId.toString(), userId.toString() //ARGV[]
        );
        int r= Objects.requireNonNull(res).intValue(); //lua脚本执行结果
        if(r!=0){
            return Result.fail(r==1?"已被抢光！来晚了！":"不能二次下单");
        }

        //有下单资格，把下单信息加入到阻塞队列
        long orderId = redisIdWorker.nextId("order");//生成全局唯一订单id
        VoucherOrder voucherOrder = VoucherOrder.builder()
                .id(orderId)
                .userId(userId)
                .voucherId(voucherId)
                .build();//生成订单对象
        //放入阻塞队列
        orderTasks.add(voucherOrder);
        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回订单id
        return Result.ok(orderId);
    }*/


    //（脚本对象）定义静态常量，并在静态代码块中完成初始化，使其只加载一次
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static{
        SECKILL_SCRIPT=new DefaultRedisScript<>();//初始化的脚本对象
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));//脚本路径
        SECKILL_SCRIPT.setResultType(Long.class);//设置返回值类型
    }
    //阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    //线程池（单线程）
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct//类初始化就执行此方法，来提交子线程执行
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    //内部类实现线程任务（基于stream消息队列）
    private class VoucherOrderHandler implements Runnable{
        String queueName = "stream.orders";//stream队列名
        @Override
        public void run() {
            while (true) {
                try {
                    //1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = template.opsForStream().read(
                            Consumer.from("g1", "c1"),//消费者（组名，消费者名）
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),//读取数，等待时间
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );

                    //2.判断消息是否获取成功
                    if(list == null || list.isEmpty()){
                        // 获取失败，没有消息，继续下一次循环
                        log.info("获取失败，没有消息，继续下一次循环");
                        continue;
                    }
                    //3.解析消息订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> map = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(map, new VoucherOrder(), true);//map -> bean

                    //4.创建订单
                    handleVoucherOrder(voucherOrder);

                    //5.ACK确认 SACK stream.orders g1 id
                    template.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                    handlePendingList();

                }
            }
        }
        //消息队列处理业务时异常，从pending-list中获取信息
        private void handlePendingList() {
            while (true) {
                try {
                    //1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = template.opsForStream().read(
                            Consumer.from("g1", "c1"),//消费者（组名，消费者名）
                            StreamReadOptions.empty().count(1),//读取数，等待时间
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );

                    //2.判断消息是否获取成功
                    if(list == null || list.isEmpty()){
                        // 获取失败，pending-list没有异常消息，退出异常获取循环
                        break;
                    }
                    //3.解析消息订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> map = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(map, new VoucherOrder(), true);//map -> bean

                    //4.创建订单
                    handleVoucherOrder(voucherOrder);

                    //5.ACK确认 SACK stream.orders g1 id
                    template.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理pending-list订单异常",e);
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException interruptedException) {
                        interruptedException.printStackTrace();
                    }
                }
            }
        }
    }




    /*//内部类实现线程任务（基于阻塞队列）
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while (true) {
                try {
                    //1.获取订单信息
                    VoucherOrder voucherOrder = orderTasks.take();//从阻塞队列中获取，没有就阻塞
                    //2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                }
            }
        }
    }*/



    //创建订单（再加分布式锁做保底）
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //获取用户
        Long userId = voucherOrder.getUserId();
        //获取锁对象
//        SimpleRedisLock lock = new SimpleRedisLock("order"+userId,template);
        RLock lock = redissonClient.getLock("lock:order"+userId);
        //尝试获取锁    ---（使用Redis实现的分布式锁解决一人多单的并发问题）
        boolean isLock = lock.tryLock();
        if(!isLock){
            //获取失败
            log.error("不能二次抢购");
            return;
        }
        try {
            //用主线程的代理对象（接口）来避免事务失效
            proxy.createVoucherOrder(voucherOrder);//创建订单
        } finally {
            //释放锁
            lock.unlock();
//            lock.unLockOfLua();
        }
    }

    /*public Result seckillVoucher2(Long voucherId) {
        //1.查询优惠券信息
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始！请稍后");
        }
        //3.判断是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已结束！您来晚了");
        }
        //4.判断库存是否充足
        if (voucher.getStock()<1) {
            return Result.fail("优惠券已抢光！");
        }
        Long userId = UserHolder.getUser().getId();
        //获取锁对象
//        SimpleRedisLock lock = new SimpleRedisLock("order"+userId,template);
        RLock lock = redissonClient.getLock("lock:order"+userId);
        //尝试获取锁    ---（使用Redis实现的分布式锁解决一人多单的并发问题）
        boolean isLock = lock.tryLock();
        if(!isLock){
            //获取失败
            return Result.fail("您已经买过此优惠券，不能二次抢购！");
        }
        try {
            //用此类的代理对象（接口）来避免事务失效
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();//获取代理对象
            return proxy.createVoucherOrder(voucherId);
        } finally {
            //释放锁
            lock.unlock();
//            lock.unLockOfLua();
        }
    }*/

    /**
     * 创建订单
     * @param voucherOrder
     */
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //5.保证一人一单
        Long userId = voucherOrder.getUserId();
        Long count = query().eq("user_id", userId)
                .eq("voucher_id", voucherOrder.getVoucherId())
                .count();
        if (count>0) {
            //用户对同一张券二次下单
            log.error("不能二次抢购！");
            return ;
        }
        //6.扣减库存
        boolean isSuccess = seckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock",0) // 乐观锁 解决超卖问题（扣减库存前 判断当前库存是否是大于零，不是就执行失败）
                .update();
        if (!isSuccess) {
            log.error("优惠券已抢光！");
            return;
        }
        //7.创建订单
        save(voucherOrder);
    }
}
