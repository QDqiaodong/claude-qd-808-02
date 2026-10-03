package com.feed.mill.domain;

import java.math.BigDecimal;

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
 * 制粒工段：调质温度、蒸汽压力、环模孔径、环模累计产量/寿命上限、颗粒硬度、粉化率。
 */
@Entity
@Table(name = "pellet")
@Getter
@Setter
public class Pellet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "batch_id", nullable = false, unique = true)
    private Batch batch;

    /** 调质温度 ℃ */
    @Column(name = "tempering_temp")
    private BigDecimal temperingTemp;

    /** 蒸汽压力 MPa */
    @Column(name = "steam_pressure")
    private BigDecimal steamPressure;

    /** 环模孔径 mm */
    @Column(name = "die_aperture")
    private BigDecimal dieAperture;

    /** 环模累计产量 吨 */
    @Column(name = "die_used_tons")
    private BigDecimal dieUsedTons;

    /** 环模寿命上限 吨 */
    @Column(name = "die_life_tons")
    private BigDecimal dieLifeTons;

    /** 颗粒硬度 N */
    @Column(name = "hardness")
    private BigDecimal hardness;

    /** 粉化率 % */
    @Column(name = "powder_rate")
    private BigDecimal powderRate;

    /** 本批制粒产量 吨 */
    @Column(name = "output_tons")
    private BigDecimal outputTons;

    /** 状态 IDLE/RUNNING/DONE/BLOCKED */
    @Column(name = "status", length = 16)
    private String status;

    /** 环模编号 */
    @Column(name = "die_code", length = 32)
    private String dieCode;
}
