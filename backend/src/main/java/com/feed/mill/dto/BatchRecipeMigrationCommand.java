package com.feed.mill.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BatchRecipeMigrationCommand {

    /** KEEP 继续使用原版本快照；MIGRATE 迁移到当前新版本 */
    @NotBlank(message = "请选择保留原版本或迁移新版本")
    private String action;

    @NotNull(message = "请选择目标配方版本")
    private Long targetRecipeId;

    /** 操作员打开确认框时看到的批次版本号；并发提交时以服务端当前值为准 */
    @NotNull(message = "缺少批次版本确认号，请刷新后重试")
    private Integer expectedLockVersion;

    @NotBlank(message = "请填写确认操作人")
    private String operatorName;

    /** 前端必须回传已阅读影响确认，防止误操作 */
    @NotNull(message = "请确认已了解版本和投料明细影响")
    private Boolean impactConfirmed;

    private String reason;
}
