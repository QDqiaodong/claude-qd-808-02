package com.feed.mill.dto;

import java.math.BigDecimal;

import lombok.Getter;
import lombok.Setter;

/**
 * 按批次统计投料误差率（JPQL 聚合结果）。
 */
@Getter
@Setter
public class BatchFeedErrorVO {

    private Long batchId;

    private String batchNo;

    /** 已投料明细数 */
    private Long itemCount;

    /** 平均误差率 %（绝对值） */
    private BigDecimal avgDeviationRate;

    /** 最大误差率 %（绝对值） */
    private BigDecimal maxDeviationRate;

    /** 异常条数 */
    private Long abnormalCount;

    /** 结论：稳定 / 关注 / 失控 */
    private String riskLevel;
}
