package com.feed.mill.dto;

import java.math.BigDecimal;

import lombok.Getter;
import lombok.Setter;

/**
 * 按畜种统计配方数与成本（JPQL 聚合结果）。
 */
@Getter
@Setter
public class SpeciesRecipeStatVO {

    /** 畜种编码 SWINE/POULTRY/AQUA/RUMINANT */
    private String species;

    /** 畜种中文名 */
    private String speciesName;

    /** 配方数 */
    private Long recipeCount;

    /** 平均吨成本 元/吨 */
    private BigDecimal avgCost;

    /** 最低吨成本 */
    private BigDecimal minCost;

    /** 最高吨成本 */
    private BigDecimal maxCost;

    /** 已发布配方数 */
    private Long releasedCount;
}
