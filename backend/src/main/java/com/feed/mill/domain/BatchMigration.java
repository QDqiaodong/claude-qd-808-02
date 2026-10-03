package com.feed.mill.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 批次配方迁移/冻结记录：操作员每次选择（迁移到新版本 / 确认冻结原版本）都留痕。
 * 全部用扁平快照列（不做 JPA 关联），配合 batch.recipe_id 可追溯批次最终采用的配方版本。
 */
@Entity
@Table(name = "batch_migration")
@Getter
@Setter
public class BatchMigration {

    /** 动作：迁移到新版本 */
    public static final String MIGRATE = "MIGRATE";
    /** 动作：冻结在原版本 */
    public static final String FREEZE = "FREEZE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    /** 批次号（快照） */
    @Column(name = "batch_no", nullable = false, length = 32)
    private String batchNo;

    /** MIGRATE 迁移到新版本 / FREEZE 冻结在原版本 */
    @Column(name = "action", nullable = false, length = 16)
    private String action;

    @Column(name = "recipe_code", nullable = false, length = 32)
    private String recipeCode;

    /** 原采用版本 */
    @Column(name = "from_recipe_id")
    private Long fromRecipeId;

    @Column(name = "from_revision_no")
    private Integer fromRevisionNo;

    /** 目标版本（FREEZE 时为被拒绝的最新版本） */
    @Column(name = "to_recipe_id")
    private Long toRecipeId;

    @Column(name = "to_revision_no")
    private Integer toRevisionNo;

    /** 计划产量 吨 */
    @Column(name = "planned_tons")
    private BigDecimal plannedTons;

    /** 批次计划成本（原版本口径 = 旧吨成本 × 计划吨数） */
    @Column(name = "cost_before")
    private BigDecimal costBefore;

    /** 批次计划成本（目标版本口径） */
    @Column(name = "cost_after")
    private BigDecimal costAfter;

    /** 操作员 */
    @Column(name = "operator_name", nullable = false, length = 32)
    private String operatorName;

    /** 操作说明 */
    @Column(name = "reason")
    private String reason;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
