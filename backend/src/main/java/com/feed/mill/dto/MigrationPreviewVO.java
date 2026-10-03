package com.feed.mill.dto;

import java.math.BigDecimal;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * 批次迁移影响预览：新旧版本差异、计划用量与成本变化，让操作员看清影响后再确认。
 */
@Getter
@Setter
public class MigrationPreviewVO {

    private Long batchId;
    private String batchNo;
    private String batchStatus;
    private String migrationStatus;

    /** 是否允许迁移/冻结（仅 PLANNED 批次；已开始投料/制粒/质检的批次必须保持原版本） */
    private Boolean canMigrate;
    /** 不允许时的原因说明 */
    private String blockReason;

    private String recipeCode;
    private Long fromRecipeId;
    private Integer fromRevisionNo;
    private Long toRecipeId;
    private Integer toRevisionNo;

    /** 计划产量 吨 */
    private BigDecimal plannedTons;
    /** 吨成本：原版本 → 新版本 */
    private BigDecimal costPerTonBefore;
    private BigDecimal costPerTonAfter;
    /** 批次计划成本：原版本 → 新版本（吨成本 × 计划吨数） */
    private BigDecimal costBefore;
    private BigDecimal costAfter;

    /** 明细差异（按投料顺序展开的新旧对照） */
    private List<ItemDiff> items;

    @Getter
    @Setter
    public static class ItemDiff {
        private String materialCode;
        private String materialName;
        /** CHANGED 占比变化 / ADDED 新增 / REMOVED 移除 / SAME 未变 */
        private String change;
        private BigDecimal ratioBefore;
        private BigDecimal ratioAfter;
        /** 计划投料量 kg：原版本 → 新版本 */
        private BigDecimal plannedKgBefore;
        private BigDecimal plannedKgAfter;
    }
}
