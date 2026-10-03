package com.feed.mill.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
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

    /** 班组/操作员 */
    @Column(name = "operator_name", length = 32)
    private String operatorName;

    @Column(name = "remark", length = 255)
    private String remark;

    /** 有序投料明细：按投料顺序（FeedingItem.FeedSeqComparator） */
    @OneToMany(mappedBy = "batch", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @SortComparator(FeedingItem.FeedSeqComparator.class)
    private SortedSet<FeedingItem> feedings = new TreeSet<>(new FeedingItem.FeedSeqComparator());
}
