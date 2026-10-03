package com.feed.mill.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 复核员录入复检检验值：全部初检项目都要给新实测值，原初检记录不改写。
 */
@Getter
@Setter
public class RecheckMeasureCommand {

    @NotBlank(message = "复核员不能为空")
    @Size(max = 32, message = "复核员最长 32 字")
    private String reviewer;

    @NotEmpty(message = "复检检验值不能为空")
    @Valid
    private List<ItemValue> items;

    @Getter
    @Setter
    public static class ItemValue {

        @NotBlank(message = "检验项编码不能为空")
        private String itemCode;

        @NotNull(message = "复检实测值不能为空")
        @DecimalMin(value = "0", message = "实测值不能为负数")
        private BigDecimal measuredValue;
    }
}
