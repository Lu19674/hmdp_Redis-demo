package com.hmdp.entity;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 滚动分页统一返回结果
 */
@Data
@Builder
public class ScrollResult {
    /**
     * 分页查询结果
     */
    private List<?> list;

    /**
     * 这一次分页查询最小时间戳（作为下一页的最大时间戳）
     */
    private Long minTime;

    /**
     * 偏移量（排序后，后缀时间戳相同的数量）
     */
    private Integer offset;
}