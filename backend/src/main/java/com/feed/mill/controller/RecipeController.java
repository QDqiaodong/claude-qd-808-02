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
import com.feed.mill.domain.RecipeRevisionLog;
import com.feed.mill.dto.RatioAdjustCommand;
import com.feed.mill.dto.RevisionCommand;
import com.feed.mill.dto.SpeciesRecipeStatVO;
import com.feed.mill.service.RecipeService;

import jakarta.validation.Valid;

/**
 * 配方与配料：占比微调、锁定平摊、发布校验、修订版本。
 * GET /api/recipes/{id} 返回的明细是 @SortNatural 有序 SortedSet。
 */
@RestController
@RequestMapping("/api/recipes")
public class RecipeController {

    private final RecipeService recipeService;

    public RecipeController(RecipeService recipeService) {
        this.recipeService = recipeService;
    }

    /** 配方列表（当前生效版本 + 修订草稿） */
    @GetMapping
    public ApiResponse<List<Recipe>> list() {
        return ApiResponse.success(recipeService.list());
    }

    /** 配方详情：明细按原料自然序排好的有序集合 */
    @GetMapping("/{id}")
    public ApiResponse<Recipe> detail(@PathVariable Long id) {
        return ApiResponse.success(recipeService.detail(id));
    }

    /** 版本链：同一配方编号的全部修订版本（含历史快照） */
    @GetMapping("/{id}/revisions")
    public ApiResponse<List<Recipe>> revisions(@PathVariable Long id) {
        return ApiResponse.success(recipeService.revisions(id));
    }

    /** 修订记录：含修订前后明细差异快照 */
    @GetMapping("/{id}/revision-logs")
    public ApiResponse<List<RecipeRevisionLog>> revisionLogs(@PathVariable Long id) {
        return ApiResponse.success(recipeService.revisionLogs(id));
    }

    /** 创建修订草稿：基于当前生效版本复制，可继续微调 */
    @PostMapping("/{id}/revise")
    public ApiResponse<Recipe> revise(@PathVariable Long id, @Valid @RequestBody RevisionCommand command) {
        return ApiResponse.success("修订草稿已创建", recipeService.revise(id, command));
    }

    /** 废弃修订草稿 */
    @PostMapping("/{id}/discard")
    public ApiResponse<Void> discard(@PathVariable Long id) {
        recipeService.discard(id);
        return ApiResponse.success("修订草稿已废弃", null);
    }

    /** 占比合计（前端堆叠条用） */
    @GetMapping("/{id}/total-ratio")
    public ApiResponse<Map<String, BigDecimal>> totalRatio(@PathVariable Long id) {
        Recipe recipe = recipeService.detail(id);
        return ApiResponse.success(Map.of("totalRatio", recipeService.totalRatio(recipe)));
    }

    /** 微调某原料占比（±0.5%，仅草稿可改） */
    @PostMapping("/{id}/adjust")
    public ApiResponse<Recipe> adjust(@PathVariable Long id, @Valid @RequestBody RatioAdjustCommand command) {
        return ApiResponse.success("占比已调整", recipeService.adjust(id, command));
    }

    /** 锁定某原料占比并自动平摊其余（仅草稿可改） */
    @PostMapping("/{id}/spread")
    public ApiResponse<Recipe> spread(@PathVariable Long id, @Valid @RequestBody RatioAdjustCommand command) {
        return ApiResponse.success("已按锁定占比平摊至 100%", recipeService.spread(id, command));
    }

    /** 发布校验：合计必须 100%；修订发布会把旧版本收口为被取代并标记受影响批次 */
    @PostMapping("/{id}/submit")
    public ApiResponse<Recipe> submit(@PathVariable Long id) {
        return ApiResponse.success("配方已发布", recipeService.submit(id));
    }

    /** 按畜种统计配方数与成本（手写 JPQL，只统计当前生效版本） */
    @GetMapping("/stats/species")
    public ApiResponse<List<SpeciesRecipeStatVO>> speciesStats() {
        return ApiResponse.success(recipeService.speciesStats());
    }
}
