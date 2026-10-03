package com.feed.mill.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 创建配方修订草稿：基于当前生效版本复制一份可编辑草稿（revision_no+1）。
 */
@Getter
@Setter
public class RevisionCommand {

    @NotBlank(message = "操作员不能为空")
    @Size(max = 32, message = "操作员最长 32 字")
    private String operatorName;

    /** 修订说明（为什么改） */
    @Size(max = 255, message = "修订说明最长 255 字")
    private String note;
}
