package com.feed.mill.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.SortedSet;
import java.util.TreeSet;

import org.hibernate.annotations.SortComparator;

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
 * 生产批次：批次号、配方、计划产量吨。
 * 投料明细用 @SortComparator + SortedSet，严格按照投料顺序（投料口次序）排成有序集合。
 */
@Entity
@Table(name = "batch")
@Getter
@Setter
public class Batch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_no", nullable = false, unique = true, length = 32)
    private String batchNo;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "recipe_id", nullable = false)
    private Recipe recipe;

    /** 计划产量 吨 */
    @Column(name = "planned_tons")
    private BigDecimal plannedTons;

    /** 实际产量 吨 */
    @Column(name = "actual_tons")
    private BigDecimal actualTons;

    @Column(name = "produce_date")
    private LocalDate produceDate;

    /** 状态 PLANNED/FEEDING/PELLETING/QC/RECHECK/DONE */
    @Column(name = "status", length = 16)
    private String status;

    /** 已投料/制粒/质检后强制冻结；仅纯 PLANNED 批次可迁移 */
    @Column(name = "recipe_version_locked", nullable = false)
    private Boolean recipeVersionLocked;

    /** 默认冻结/确认保留/开工冻结；迁移后为空 */
    @Column(name = "version_freeze_reason", length = 16)
    private String versionFreezeReason;

    /** 当前冻结版本对应的计划总成本 */
    @Column(name = "planned_cost")
    private BigDecimal plannedCost;

    /** 最近一次操作员 KEEP/MIGRATE 确认时间，用于并发重复提交检测 */
    @Column(name = "recipe_decision_at")
    private LocalDateTime recipeDecisionAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Integer lockVersion;

    /** 班组/操作员 */
    @Column(name = "operator_name", length = 32)
    private String operatorName;

    @Column(name = "remark", length = 255)
    private String remark;

    /** 有序投料明细：按投料顺序（FeedingItem.FeedSeqComparator） */
    @OneToMany(mappedBy = "batch", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @SortComparator(FeedingItem.FeedSeqComparator.class)
    private SortedSet<FeedingItem> feedings = new TreeSet<>(new FeedingItem.FeedSeqComparator());

    /** 同一配方族是否存在可迁移的新已发布版本（列表/详情接口由 service 填充） */
    @Transient
    private Recipe currentRecipe;

    @Transient
    private Boolean migrationAvailable;

    @Transient
    private String migrationBlockReason;
}
