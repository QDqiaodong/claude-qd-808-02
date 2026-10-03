package com.feed.mill.dto;

import java.math.BigDecimal;

import lombok.Getter;
import lombok.Setter;

/**
 * 质检不合格项分布（JPQL 聚合结果）。
 */
@Getter
@Setter
public class QcFailDistVO {

    private String itemCode;

    private String itemName;

    /** 检验次数 */
    private Long checkCount;

    /** 不合格次数 */
    private Long failCount;

    /** 不合格率 % */
    private BigDecimal failRate;
}
