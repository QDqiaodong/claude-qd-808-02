package com.feed.mill.domain;

import java.math.BigDecimal;
import java.util.SortedSet;
import java.util.TreeSet;

import org.hibernate.annotations.SortNatural;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 配方：配方编号、适用畜种、阶段、营养指标（粗蛋白/代谢能/赖氨酸）。
 * 明细用 Hibernate 的 @SortNatural + SortedSet，始终按原料编码的自然序排列。
 */
@Entity
@Table(name = "recipe")
@Getter
@Setter
public class Recipe {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recipe_code", nullable = false, unique = true, length = 32)
    private String recipeCode;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    /** 适用畜种 SWINE/POULTRY/AQUA/RUMINANT */
    @Column(name = "species", nullable = false, length = 16)
    private String species;

    /** 阶段 SUCKLING/GROWER/FINISHER/LAYER/BROILER ... */
    @Column(name = "stage", length = 16)
    private String stage;

    /** 粗蛋白 % */
    @Column(name = "crude_protein")
    private BigDecimal crudeProtein;

    /** 代谢能 MJ/kg */
    @Column(name = "metabolic_energy")
    private BigDecimal metabolicEnergy;

    /** 赖氨酸 % */
    @Column(name = "lysine")
    private BigDecimal lysine;

    /** 状态 DRAFT/RELEASED */
    @Column(name = "status", length = 16)
    private String status;

    /** 吨成本 元/吨（明细成本小计合计） */
    @Column(name = "cost_per_ton")
    private BigDecimal costPerTon;

    /** 有序明细集合：按原料编码自然序 */
    @OneToMany(mappedBy = "recipe", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @SortNatural
    private SortedSet<RecipeItem> items = new TreeSet<>();
}
