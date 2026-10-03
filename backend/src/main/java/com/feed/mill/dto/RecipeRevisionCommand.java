package com.feed.mill.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RecipeRevisionCommand {

    /** 发布/创建修订的操作人 */
    @NotBlank(message = "请填写操作人")
    private String operatorName;

    /** 修订说明 */
    private String changeSummary;
}
