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
 * <p>
 * 修订版本：同一 recipe_code 下每个修订版本一行（revision_no 递增）。
 * 状态机 DRAFT → RELEASED → SUPERSEDED；SUPERSEDED 行是被取代版本的历史快照，永不改写，
 * 批次引用哪一行就锁定在哪一版。active_flag=1 仅当前生效版本（唯一索引 uk_recipe_active 收口）。
 */
@Entity
@Table(name = "recipe")
@Getter
@Setter
public class Recipe {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recipe_code", nullable = false, length = 32)
    private String recipeCode;

    /** 修订版本号（1 起） */
    @Column(name = "revision_no", nullable = false)
    private Integer revisionNo = 1;

    /** 基于哪个版本修订（上一版本 id，首次发布为 null） */
    @Column(name = "based_on_id")
    private Long basedOnId;

    /** 修订说明 */
    @Column(name = "revision_note")
    private String revisionNote;

    /** 修订人（创建该版本的操作员，发布修订时写入修订记录） */
    @Column(name = "operator_name", length = 32)
    private String operatorName;

    /** 当前生效=1，草稿/被取代=NULL（唯一索引 uk_recipe_active 的收口开关） */
    @Column(name = "active_flag")
    private Integer activeFlag;

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

    /** 状态 DRAFT/RELEASED/SUPERSEDED */
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
