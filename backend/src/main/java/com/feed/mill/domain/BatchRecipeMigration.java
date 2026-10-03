package com.feed.mill.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 批次最终采用的配方版本决策记录：保留旧快照、迁移新版本或开工自动冻结均落表可追溯。
 */
@Entity
@Table(name = "batch_recipe_migration")
@Getter
@Setter
public class BatchRecipeMigration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private Batch batch;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "from_recipe_id", nullable = false)
    private Recipe fromRecipe;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "to_recipe_id", nullable = false)
    private Recipe toRecipe;

    /** KEEP/MIGRATE/FREEZE */
    @Column(name = "action", nullable = false, length = 16)
    private String action;

    @Column(name = "operator_name", nullable = false, length = 32)
    private String operatorName;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "old_cost_per_ton")
    private BigDecimal oldCostPerTon;

    @Column(name = "new_cost_per_ton")
    private BigDecimal newCostPerTon;

    @Column(name = "old_planned_cost")
    private BigDecimal oldPlannedCost;

    @Column(name = "new_planned_cost")
    private BigDecimal newPlannedCost;

    @Column(name = "regenerated_item_count")
    private Integer regeneratedItemCount;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
