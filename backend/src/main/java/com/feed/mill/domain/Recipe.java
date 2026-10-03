package com.feed.mill.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.SortedSet;
import java.util.TreeSet;

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.hibernate.annotations.SortNatural;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * 配方修订版本。已发布版本不可直接修改；需要变更时复制为下一版 DRAFT，发布后成为当前版本。
 */
@Entity
@Table(name = "recipe")
@Getter
@Setter
public class Recipe {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 配方族编号，跨版本保持一致 */
    @Column(name = "recipe_code", nullable = false, length = 32)
    private String recipeCode;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "family_key", nullable = false, length = 48)
    private String familyKey;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_recipe_id")
    private Recipe sourceRecipe;

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

    /** 当前已发布版本标记，唯一索引保证同一配方族同时只有一个当前版本 */
    @Column(name = "current_flag")
    private Integer currentFlag;

    /** 进行中修订标记，唯一索引保证同一配方族同时只有一个 DRAFT */
    @Column(name = "active_draft_flag")
    private Integer activeDraftFlag;

    /** 版本发布时吨成本快照 元/吨 */
    @Column(name = "cost_per_ton")
    private BigDecimal costPerTon;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "published_by", length = 32)
    private String publishedBy;

    @Column(name = "change_summary", length = 255)
    private String changeSummary;

    /** 多人同时发布同一修订时，只有一次条件更新生效 */
    @Version
    @Column(name = "lock_version", nullable = false)
    private Integer lockVersion;

    /** 有序明细集合：按原料编码自然序 */
    @OneToMany(mappedBy = "recipe", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @SortNatural
    private SortedSet<RecipeItem> items = new TreeSet<>();

    @Transient
    public Long getSourceRecipeId() {
        return sourceRecipe == null ? null : sourceRecipe.getId();
    }
}
