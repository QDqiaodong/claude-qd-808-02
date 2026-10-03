package com.feed.mill.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Getter;
import lombok.Setter;

/**
 * 每日产量（近 7 日折线用）。
 */
@Getter
@Setter
public class DailyOutputVO {

    private LocalDate date;

    private Long batchCount;

    private BigDecimal outputTons;

    public DailyOutputVO() {
    }

    public DailyOutputVO(LocalDate date, Long batchCount, BigDecimal outputTons) {
        this.date = date;
        this.batchCount = batchCount;
        this.outputTons = outputTons;
    }
}
