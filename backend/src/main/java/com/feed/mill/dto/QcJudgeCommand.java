package com.feed.mill.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 质检判定：结论 PASS/DOWNGRADE/REJECT + 处理去向。
 */
@Getter
@Setter
public class QcJudgeCommand {

    @NotBlank(message = "判定结论不能为空")
    private String conclusion;

    /** 处理去向 */
    private String disposition;

    private String remark;
}
