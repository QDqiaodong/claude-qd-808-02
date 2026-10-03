package com.feed.mill.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.Getter;
import lombok.Setter;

/**
 * 制粒工艺参数调整：调质温度、蒸汽压力、环模孔径。
 */
@Getter
@Setter
public class PelletAdjustCommand {

    /** 调质温度 ℃ */
    @DecimalMin(value = "60", message = "调质温度不得低于 60℃")
    @DecimalMax(value = "95", message = "调质温度不得高于 95℃")
    private BigDecimal temperingTemp;

    /** 蒸汽压力 MPa */
    @DecimalMin(value = "0.10", message = "蒸汽压力不得低于 0.10MPa")
    @DecimalMax(value = "0.60", message = "蒸汽压力不得高于 0.60MPa")
    private BigDecimal steamPressure;

    /** 环模孔径 mm */
    @DecimalMin(value = "1.5", message = "环模孔径不得小于 1.5mm")
    @DecimalMax(value = "6.0", message = "环模孔径不得大于 6.0mm")
    private BigDecimal dieAperture;

    /** 本批计划制粒产量 吨 */
    private BigDecimal outputTons;
}
