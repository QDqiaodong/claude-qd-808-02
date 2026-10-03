package com.feed.mill.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 原料与筒仓：库存吨、水分含量%、单价、供应商、安全库存、入仓批次。
 */
@Entity
@Table(name = "material")
@Getter
@Setter
public class Material {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 原料编码 */
    @Column(name = "material_code", nullable = false, unique = true, length = 32)
    private String materialCode;

    /** 原料名称 */
    @Column(name = "name", nullable = false, length = 64)
    private String name;

    /** 筒仓编号 */
    @Column(name = "silo_code", nullable = false, length = 16)
    private String siloCode;

    /** 筒仓名称 */
    @Column(name = "silo_name", length = 64)
    private String siloName;

    /** 类别：能量饲料/蛋白饲料/矿物质/添加剂 */
    @Column(name = "category", length = 32)
    private String category;

    /** 库存吨 */
    @Column(name = "stock_tons")
    private BigDecimal stockTons;

    /** 水分含量 % */
    @Column(name = "moisture_pct")
    private BigDecimal moisturePct;

    /** 单价 元/吨 */
    @Column(name = "unit_price")
    private BigDecimal unitPrice;

    /** 供应商 */
    @Column(name = "supplier", length = 128)
    private String supplier;

    /** 安全库存吨 */
    @Column(name = "safety_stock")
    private BigDecimal safetyStock;

    /** 入仓批次 */
    @Column(name = "inbound_batch", length = 32)
    private String inboundBatch;

    /** 单一原料在配方中的占比上限 %（鱼粉 8%、预混料 4% 等） */
    @Column(name = "max_ratio")
    private BigDecimal maxRatio;
}
