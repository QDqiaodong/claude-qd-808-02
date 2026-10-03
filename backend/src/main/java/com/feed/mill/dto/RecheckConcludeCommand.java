package com.feed.mill.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 复核员提交最终结论：放行 / 降级 / 继续退货。
 * 霉菌毒素仍超标只能继续退货；全部项目恢复标准范围才允许放行。
 */
@Getter
@Setter
public class RecheckConcludeCommand {

    @NotBlank(message = "复核员不能为空")
    @Size(max = 32, message = "复核员最长 32 字")
    private String reviewer;

    @NotBlank(message = "最终结论不能为空")
    private String conclusion;

    /** 处理去向（降级情形必填明确去向，留空走默认） */
    @Size(max = 64, message = "处理去向最长 64 字")
    private String disposition;

    @Size(max = 255, message = "备注最长 255 字")
    private String remark;
}
