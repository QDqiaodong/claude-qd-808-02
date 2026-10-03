package com.feed.mill.dto;

import java.math.BigDecimal;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * 工段概览：三张指标卡 + 近 7 日产量折线 + 低库存预警。
 */
@Getter
@Setter
public class DashboardVO {

    /** 今日批次 */
    private Long todayBatchCount;

    /** 累计产量 吨 */
    private BigDecimal cumulativeOutputTons;

    /** 合格率 % */
    private BigDecimal passRate;

    /** 运行中工段数 */
    private Long runningCount;

    /** 低库存原料数 */
    private Long lowStockCount;

    /** 近 7 日产量 */
    private List<DailyOutputVO> trend;
}
