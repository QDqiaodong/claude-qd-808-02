package com.feed.mill.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;

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
 * 投料明细：投料顺序（投料口）、计划量、实际投料量、误差、状态。
 * 顺序由 {@link FeedSeqComparator} 定义，父批次用 {@code @SortComparator} 维持有序 TreeSet。
 */
@Entity
@Table(name = "feeding_item")
@Getter
@Setter
public class FeedingItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private Batch batch;

    /** 投料顺序（第几个投料口） */
    @Column(name = "feed_seq", nullable = false)
    private Integer feedSeq;

    /** 投料口编号 P1/P2/... */
    @Column(name = "port_no", length = 16)
    private String portNo;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "material_id", nullable = false)
    private Material material;

    /** 计划投料量 kg */
    @Column(name = "planned_kg")
    private BigDecimal plannedKg;

    /** 实际投料量 kg */
    @Column(name = "actual_kg")
    private BigDecimal actualKg;

    /** 误差率 %（正为超投、负为欠投） */
    @Column(name = "deviation_rate")
    private BigDecimal deviationRate;

    /** 状态 PENDING/WEIGHING/DONE/ABNORMAL */
    @Column(name = "status", length = 16)
    private String status;

    /** 异常说明 */
    @Column(name = "abnormal_note", length = 255)
    private String abnormalNote;

    @Column(name = "weighed_at")
    private LocalDateTime weighedAt;

    /**
     * 投料先后顺序：先按投料顺序号，再按投料口编号，最后按原料编码兜底。
     */
    public static class FeedSeqComparator implements Comparator<FeedingItem> {

        @Override
        public int compare(FeedingItem a, FeedingItem b) {
            int bySeq = Comparator.nullsLast(Comparator.<Integer>naturalOrder()).compare(a.getFeedSeq(), b.getFeedSeq());
            if (bySeq != 0) {
                return bySeq;
            }
            int byPort = Comparator.nullsFirst(Comparator.<String>naturalOrder()).compare(a.getPortNo(), b.getPortNo());
            if (byPort != 0) {
                return byPort;
            }
            String codeA = a.getMaterial() == null ? null : a.getMaterial().getMaterialCode();
            String codeB = b.getMaterial() == null ? null : b.getMaterial().getMaterialCode();
            int byCode = Comparator.nullsFirst(Comparator.<String>naturalOrder()).compare(codeA, codeB);
            if (byCode != 0) {
                return byCode;
            }
            return Comparator.nullsFirst(Comparator.<Long>naturalOrder()).compare(a.getId(), b.getId());
        }
    }
}
