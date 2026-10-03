package com.feed.mill.controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.feed.mill.common.ApiResponse;
import com.feed.mill.domain.Recipe;
import com.feed.mill.domain.RecipeRevisionDiff;
import com.feed.mill.dto.RatioAdjustCommand;
import com.feed.mill.dto.RecipeRevisionCommand;
import com.feed.mill.dto.SpeciesRecipeStatVO;
import com.feed.mill.service.RecipeService;

import jakarta.validation.Valid;

/**
 * 配方与配料：占比微调、锁定平摊、发布校验。
 * GET /api/recipes/{id} 返回的明细是 @SortNatural 有序 SortedSet。
 */
@RestController
@RequestMapping("/api/recipes")
public class RecipeController {

    private final RecipeService recipeService;

    public RecipeController(RecipeService recipeService) {
        this.recipeService = recipeService;
    }

    /** 配方列表（含明细与营养指标） */
    @GetMapping
    public ApiResponse<List<Recipe>> list() {
        return ApiResponse.success(recipeService.list());
    }

    /** 配方详情：明细按原料自然序排好的有序集合 */
    @GetMapping("/{id}")
    public ApiResponse<Recipe> detail(@PathVariable Long id) {
        return ApiResponse.success(recipeService.detail(id));
    }

    /** 修订版本列表 */
    @GetMapping("/{id}/versions")
    public ApiResponse<List<Recipe>> versions(@PathVariable Long id) {
        return ApiResponse.success(recipeService.versions(id));
    }

    /** 修订前后差异 */
    @GetMapping("/{id}/diffs")
    public ApiResponse<List<RecipeRevisionDiff>> diffs(@PathVariable Long id) {
        return ApiResponse.success(recipeService.revisionDiffs(id));
    }

    /** 基于当前发布版本复制一份新的修订草稿 */
    @PostMapping("/{id}/revisions")
    public ApiResponse<Recipe> createRevision(@PathVariable Long id,
                                              @Valid @RequestBody(required = false) RecipeRevisionCommand command) {
        return ApiResponse.success("已创建修订草稿，原发布版本保持只读", recipeService.createRevision(id, command));
    }

    /** 占比合计（前端堆叠条用） */
    @GetMapping("/{id}/total-ratio")
    public ApiResponse<Map<String, BigDecimal>> totalRatio(@PathVariable Long id) {
        Recipe recipe = recipeService.detail(id);
        return ApiResponse.success(Map.of("totalRatio", recipeService.totalRatio(recipe)));
    }

    /** 微调某原料占比（±0.5%） */
    @PostMapping("/{id}/adjust")
    public ApiResponse<Recipe> adjust(@PathVariable Long id, @Valid @RequestBody RatioAdjustCommand command) {
        return ApiResponse.success("占比已调整", recipeService.adjust(id, command));
    }

    /** 锁定某原料占比并自动平摊其余 */
    @PostMapping("/{id}/spread")
    public ApiResponse<Recipe> spread(@PathVariable Long id, @Valid @RequestBody RatioAdjustCommand command) {
        return ApiResponse.success("已按锁定占比平摊至 100%", recipeService.spread(id, command));
    }

    /** 发布修订：校验合计/上限，原子切换当前版本并保留差异 */
    @PostMapping("/{id}/submit")
    public ApiResponse<Recipe> submit(@PathVariable Long id,
                                      @Valid @RequestBody(required = false) RecipeRevisionCommand command) {
        return ApiResponse.success("修订版本已发布；未开工批次默认继续使用原版本快照",
                recipeService.submit(id, command));
    }

    /** 按畜种统计配方数与成本（手写 JPQL） */
    @GetMapping("/stats/species")
    public ApiResponse<List<SpeciesRecipeStatVO>> speciesStats() {
        return ApiResponse.success(recipeService.speciesStats());
    }
}
