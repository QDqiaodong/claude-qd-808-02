package com.feed.mill.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 发起退货复检：只有初检结论为 REJECT 的批次允许发起。
 */
@Getter
@Setter
public class RecheckCreateCommand {

    @NotBlank(message = "发起人不能为空")
    @Size(max = 32, message = "发起人最长 32 字")
    private String initiator;

    @NotBlank(message = "复检原因不能为空")
    @Size(max = 255, message = "复检原因最长 255 字")
    private String reason;
}
