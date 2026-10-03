package com.feed.mill.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 批次配方迁移选择：MIGRATE 迁移到最新生效版本（重建投料明细），
 * 或 FREEZE 确认冻结在当前版本快照。两种选择都留痕。
 */
@Getter
@Setter
public class MigrationCommand {

    /** MIGRATE / FREEZE */
    @NotBlank(message = "操作类型不能为空")
    private String action;

    @NotBlank(message = "操作员不能为空")
    @Size(max = 32, message = "操作员最长 32 字")
    private String operatorName;

    /** 操作说明（可选） */
    @Size(max = 255, message = "操作说明最长 255 字")
    private String reason;
}
