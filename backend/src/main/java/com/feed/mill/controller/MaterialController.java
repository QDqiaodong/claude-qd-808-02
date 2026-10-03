package com.feed.mill.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.feed.mill.common.ApiResponse;
import com.feed.mill.domain.Material;
import com.feed.mill.service.MaterialService;

/**
 * 原料与筒仓。
 */
@RestController
@RequestMapping("/api/materials")
public class MaterialController {

    private final MaterialService materialService;

    public MaterialController(MaterialService materialService) {
        this.materialService = materialService;
    }

    /** 全部原料（含筒仓、库存、水分、单价） */
    @GetMapping
    public ApiResponse<List<Material>> list() {
        return ApiResponse.success(materialService.list());
    }

    /** 低于安全库存的原料 */
    @GetMapping("/low-stock")
    public ApiResponse<List<Material>> lowStock() {
        return ApiResponse.success(materialService.lowStock());
    }

    /** 筒仓总览：库存吨、库存金额、缺料品种数 */
    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary() {
        return ApiResponse.success(materialService.summary());
    }

    @GetMapping("/{id}")
    public ApiResponse<Material> detail(@PathVariable Long id) {
        return ApiResponse.success(materialService.get(id));
    }
}
