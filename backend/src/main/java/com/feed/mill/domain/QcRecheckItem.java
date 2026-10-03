package com.feed.mill.domain;

import java.math.BigDecimal;
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
 * 复检检验项：复核员录入的新实测值，标准区间快照自初检。
 * 与 {@link QcItem} 一样实现 Comparable，配合 {@code @SortNatural} 按检验次序输出。
 */
@Entity
@Table(name = "qc_recheck_item")
@Getter
@Setter
public class QcRecheckItem implements Comparable<QcRecheckItem> {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "recheck_id", nullable = false)
    private QcRecheck recheck;

    /** 检验项编码 MOISTURE/PROTEIN/MYCOTOXIN/ASH */
    @Column(name = "item_code", length = 32)
    private String itemCode;

    @Column(name = "item_name", length = 32)
    private String itemName;

    /** 检验次序 */
    @Column(name = "check_order")
    private Integer checkOrder;

    @Column(name = "unit", length = 16)
    private String unit;

    /** 复检实测值 */
    @Column(name = "measured_value")
    private BigDecimal measuredValue;

    /** 标准下限（快照自初检） */
    @Column(name = "std_lower")
    private BigDecimal stdLower;

    /** 标准上限（快照自初检） */
    @Column(name = "std_upper")
    private BigDecimal stdUpper;

    /** 单项是否合格 */
    @Column(name = "qualified")
    private Boolean qualified;

    @Override
    public int compareTo(QcRecheckItem other) {
        if (other == null) {
            return 1;
        }
        int byOrder = Comparator.nullsLast(Comparator.<Integer>naturalOrder())
                .compare(this.checkOrder, other.checkOrder);
        if (byOrder != 0) {
            return byOrder;
        }
        int byCode = Comparator.nullsFirst(Comparator.<String>naturalOrder()).compare(this.itemCode, other.itemCode);
        if (byCode != 0) {
            return byCode;
        }
        return Comparator.nullsFirst(Comparator.<Long>naturalOrder()).compare(this.id, other.id);
    }
}
