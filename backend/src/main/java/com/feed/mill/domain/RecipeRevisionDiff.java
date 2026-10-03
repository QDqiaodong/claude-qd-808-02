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
 * 配方修订差异。发布新版本时按旧版本快照生成，后续不再改写。
 */
@Entity
@Table(name = "recipe_revision_diff")
@Getter
@Setter
public class RecipeRevisionDiff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipe_id", nullable = false)
    private Recipe recipe;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_recipe_id", nullable = false)
    private Recipe sourceRecipe;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "material_id")
    private Material material;

    @Column(name = "item_code", nullable = false, length = 32)
    private String itemCode;

    @Column(name = "item_name", nullable = false, length = 64)
    private String itemName;

    /** ADDED/REMOVED/MODIFIED */
    @Column(name = "change_type", nullable = false, length = 16)
    private String changeType;

    @Column(name = "old_ratio_pct")
    private BigDecimal oldRatioPct;

    @Column(name = "new_ratio_pct")
    private BigDecimal newRatioPct;

    @Column(name = "old_cost_subtotal")
    private BigDecimal oldCostSubtotal;

    @Column(name = "new_cost_subtotal")
    private BigDecimal newCostSubtotal;

    @Column(name = "old_value", length = 64)
    private String oldValue;

    @Column(name = "new_value", length = 64)
    private String newValue;

    @Column(name = "unit", length = 16)
    private String unit;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
