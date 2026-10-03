package com.feed.mill.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 配方占比微调：对某条明细加/减若干百分点（前端固定 ±0.5%），
 * 或锁定该明细占比后由后端自动平摊其余原料。
 */
@Getter
@Setter
public class RatioAdjustCommand {

    @NotNull(message = "明细 id 不能为空")
    private Long itemId;

    /** 增量百分点，可为负；spread=true 时忽略 */
    private BigDecimal delta;

    /** 是否锁定 */
    private Boolean locked;

    /** true = 锁定该明细并自动平摊其余原料至 100% */
    private Boolean spread;
}
