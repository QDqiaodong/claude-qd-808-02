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
 * 配方明细：原料、占比%、成本小计。
 * 实现 Comparable —— 配合 {@code @SortNatural} 让父配方拿到的是一棵有序 TreeSet。
 */
@Entity
@Table(name = "recipe_item")
@Getter
@Setter
public class RecipeItem implements Comparable<RecipeItem> {

    private static final Comparator<String> CODE_ORDER = Comparator.nullsFirst(Comparator.naturalOrder());

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "recipe_id", nullable = false)
    private Recipe recipe;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "material_id", nullable = false)
    private Material material;

    /** 占比 % */
    @Column(name = "ratio_pct")
    private BigDecimal ratioPct;

    /** 成本小计 元/吨 = 占比% × 单价 ÷ 100 */
    @Column(name = "cost_subtotal")
    private BigDecimal costSubtotal;

    /** 是否锁定占比（锁定后自动平摊其余） */
    @Column(name = "locked")
    private Boolean locked;

    @Column(name = "sort_hint")
    private Integer sortHint;

    /**
     * 自然序：先按排序提示（原料大类顺序），再按原料编码，最后按主键兜底（避免 TreeSet 去重）。
     */
    @Override
    public int compareTo(RecipeItem other) {
        if (other == null) {
            return 1;
        }
        int byHint = Comparator.nullsFirst(Comparator.<Integer>naturalOrder())
                .compare(this.sortHint, other.sortHint);
        if (byHint != 0) {
            return byHint;
        }
        String thisCode = this.material == null ? null : this.material.getMaterialCode();
        String otherCode = other.material == null ? null : other.material.getMaterialCode();
        int byCode = CODE_ORDER.compare(thisCode, otherCode);
        if (byCode != 0) {
            return byCode;
        }
        return Comparator.nullsFirst(Comparator.<Long>naturalOrder()).compare(this.id, other.id);
    }
}
