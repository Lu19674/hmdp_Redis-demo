package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private IVoucherService voucherService;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker idWorker;

    /**
     * 优惠券下单
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
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
        synchronized (userId.toString().intern()) { //上悲观锁 解决一人多单问题
            //用此类的代理对象（接口）来避免事务失效
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();//获取代理对象
            return proxy.createVoucherOrder(voucherId);
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //5.保证一人一单
        Long userId = UserHolder.getUser().getId();
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count>0) {
            //用户对同一张券二次下单
            return Result.fail("用户已经买过此优惠券，不能二次抢购！");
        }
        //6.扣减库存
        boolean isSuccess = seckillVoucherService.update().setSql("stock = stock - 1")
                .ge("voucher_id", voucherId)
                .gt("stock",0) // 乐观锁 解决超卖问题（扣减库存前 判断当前库存是否是大于零，不是就执行失败）
                .update();
        if (!isSuccess) {
            return Result.fail("优惠券已抢光！");
        }
        //7.创建订单
        //7.1订单主键（全局唯一id）
        long orderId = idWorker.nextId("order");
        //7.2下单用户id（userId）
        //7.3代金券id （voucherId）
        //7.4封装存库
        VoucherOrder voucherOrder = VoucherOrder.builder()
                .id(orderId)
                .userId(userId)
                .voucherId(voucherId)
                .build();
        save(voucherOrder);
        return Result.ok(orderId);
    }
}
