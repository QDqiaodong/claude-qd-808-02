package com.feed.mill.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 质检抽样：抽样批次、结论（合格/降级/退货）、处理去向。
 * 检验项用 @SortNatural + SortedSet，按检验项固定次序排列。
 */
@Entity
@Table(name = "qc_sample")
@Getter
@Setter
public class QcSample {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sample_no", nullable = false, unique = true, length = 32)
    private String sampleNo;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private Batch batch;

    @Column(name = "sample_time")
    private LocalDateTime sampleTime;

    @Column(name = "sampler", length = 32)
    private String sampler;

    /** 判定结论 PASS/DOWNGRADE/REJECT */
    @Column(name = "conclusion", length = 16)
    private String conclusion;

    /** 处理去向 */
    @Column(name = "disposition", length = 64)
    private String disposition;

    /** 综合得分（用于概览展示） */
    @Column(name = "score")
    private BigDecimal score;

    @Column(name = "remark", length = 255)
    private String remark;

    /** 有序检验项集合 */
    @OneToMany(mappedBy = "sample", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @SortNatural
    private SortedSet<QcItem> items = new TreeSet<>();
}
