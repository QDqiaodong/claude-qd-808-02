package com.feed.mill.domain;

import java.time.LocalDateTime;
import java.util.SortedSet;
import java.util.TreeSet;

import org.hibernate.annotations.SortNatural;

import com.fasterxml.jackson.annotation.JsonIgnore;

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
 * 退货复检单：挂在初检抽样单上的二次检验流程。
 * 初检 qc_sample / qc_item 是原始证据，复检只新增记录，永不改写。
 * 并发收口：进行中 active_flag=1，数据库唯一索引 (batch_id, active_flag) 保证一批次一单；
 * 最终结论用条件更新（active_flag=1 才允许盖章）保证只有一个结论生效。
 */
@Entity
@Table(name = "qc_recheck")
@Getter
@Setter
public class QcRecheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recheck_no", nullable = false, unique = true, length = 32)
    private String recheckNo;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "sample_id", nullable = false)
    private QcSample sample;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private Batch batch;

    /** 发起人 */
    @Column(name = "initiator", nullable = false, length = 32)
    private String initiator;

    /** 复检原因 */
    @Column(name = "reason", nullable = false)
    private String reason;

    /** PENDING 待复核 / MEASURING 复检中 / DONE 已完成 */
    @Column(name = "status", nullable = false, length = 16)
    private String status = "PENDING";

    /** 进行中=1，完成=NULL（唯一索引 uk_recheck_active 的收口开关） */
    @Column(name = "active_flag")
    private Integer activeFlag = 1;

    /** 复核员（必须不同于初检判定人，接手后锁定） */
    @Column(name = "reviewer", length = 32)
    private String reviewer;

    /** 最终结论 RELEASE 放行 / DOWNGRADE 降级 / REJECT 继续退货 */
    @Column(name = "final_conclusion", length = 16)
    private String finalConclusion;

    /** 最终去向 */
    @Column(name = "final_disposition", length = 64)
    private String finalDisposition;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "remark")
    private String remark;

    /** 复检检验值（新记录，初检 qc_item 不动） */
    @OneToMany(mappedBy = "recheck", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @SortNatural
    private SortedSet<QcRecheckItem> items = new TreeSet<>();

    // ---- 序列化用的扁平字段：关联实体不整体输出，避免嵌套过深 ----

    public Long getSampleId() {
        return sample == null ? null : sample.getId();
    }

    public String getSampleNo() {
        return sample == null ? null : sample.getSampleNo();
    }

    public Long getBatchId() {
        return batch == null ? null : batch.getId();
    }

    public String getBatchNo() {
        return batch == null ? null : batch.getBatchNo();
    }

    /** 初检判定人：复核员必须与此不同 */
    public String getFirstInspector() {
        return sample == null ? null : sample.getSampler();
    }
}
