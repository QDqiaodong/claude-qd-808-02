package com.feed.mill.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 投料指令：开始称量（WEIGH）、取消称量（CANCEL）或确认实际投料量（CONFIRM）。
 */
@Getter
@Setter
public class FeedingCommand {

    /** WEIGH / CANCEL / CONFIRM */
    private String action;

    @NotNull(message = "投料顺序不能为空")
    private Integer feedSeq;

    /** 实际称重 kg（CONFIRM 必填） */
    private BigDecimal actualKg;
}
