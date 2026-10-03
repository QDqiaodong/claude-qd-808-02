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
 * 配方修订记录：每次发布修订写一条，含修订前后明细差异快照（diff_json）。
 * 全部用扁平快照列（不做 JPA 关联）：即使后续版本继续演进，历史差异也保持发布当刻的口径。
 */
@Entity
@Table(name = "recipe_revision_log")
@Getter
@Setter
public class RecipeRevisionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recipe_code", nullable = false, length = 32)
    private String recipeCode;

    /** 旧版本 id（首次发布为 null） */
    @Column(name = "from_recipe_id")
    private Long fromRecipeId;

    @Column(name = "to_recipe_id", nullable = false)
    private Long toRecipeId;

    @Column(name = "from_revision_no")
    private Integer fromRevisionNo;

    @Column(name = "to_revision_no", nullable = false)
    private Integer toRevisionNo;

    /** 发布人 */
    @Column(name = "operator_name", nullable = false, length = 32)
    private String operatorName;

    /** 修订说明 */
    @Column(name = "note")
    private String note;

    /** 发布时被标记为「待选择」的计划中批次数 */
    @Column(name = "affected_batches")
    private Integer affectedBatches;

    /** 旧吨成本 元/吨 */
    @Column(name = "cost_before")
    private BigDecimal costBefore;

    /** 新吨成本 元/吨 */
    @Column(name = "cost_after")
    private BigDecimal costAfter;

    /** 修订前后明细差异快照（JSON：原料、新旧占比、新旧成本小计、增删改标记） */
    @Column(name = "diff_json", columnDefinition = "TEXT")
    private String diffJson;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
