package com.feed.mill.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.feed.mill.common.ApiResponse;
import com.feed.mill.domain.Material;
import com.feed.mill.dto.DashboardVO;
import com.feed.mill.dto.SpeciesRecipeStatVO;
import com.feed.mill.service.MaterialService;
import com.feed.mill.service.RecipeService;
import com.feed.mill.service.StatsService;

/**
 * 工段概览：指标卡、近 7 日产量、缺料预警（Redis 缓存 30 秒）。
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService statsService;
    private final MaterialService materialService;
    private final RecipeService recipeService;

    public StatsController(StatsService statsService, MaterialService materialService, RecipeService recipeService) {
        this.statsService = statsService;
        this.materialService = materialService;
        this.recipeService = recipeService;
    }

    /** 概览：今日批次 / 累计产量 / 合格率 / 近 7 日折线 */
    @GetMapping("/dashboard")
    public ApiResponse<DashboardVO> dashboard() {
        return ApiResponse.success(statsService.dashboard());
    }

    /** 缺料预警 */
    @GetMapping("/low-stock")
    public ApiResponse<List<Material>> lowStock() {
        return ApiResponse.success(materialService.lowStock());
    }

    /** 配方成本画像 */
    @GetMapping("/recipe-species")
    public ApiResponse<List<SpeciesRecipeStatVO>> recipeSpecies() {
        return ApiResponse.success(recipeService.speciesStats());
    }
}
