package com.feed.mill.controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.feed.mill.common.ApiResponse;
import com.feed.mill.domain.Pellet;
import com.feed.mill.dto.PelletAdjustCommand;
import com.feed.mill.service.PelletService;

import jakarta.validation.Valid;

/**
 * 制粒工段：工艺参数、环模寿命消耗。
 */
@RestController
@RequestMapping("/api/pellets")
public class PelletController {

    private final PelletService pelletService;

    public PelletController(PelletService pelletService) {
        this.pelletService = pelletService;
    }

    /** 制粒记录（按环模寿命消耗倒序） */
    @GetMapping
    public ApiResponse<List<Pellet>> list() {
        return ApiResponse.success(pelletService.list());
    }

    @GetMapping("/batch/{batchId}")
    public ApiResponse<Pellet> byBatch(@PathVariable Long batchId) {
        return ApiResponse.success(pelletService.getByBatch(batchId));
    }

    /** 调整工艺参数，返回推荐环模寿命消耗 */
    @PostMapping("/batch/{batchId}/adjust")
    public ApiResponse<Map<String, Object>> adjust(@PathVariable Long batchId,
                                                   @Valid @RequestBody PelletAdjustCommand command) {
        return ApiResponse.success(pelletService.adjust(batchId, command));
    }

    /** 本批制粒产出：超环模寿命上限则拦截 */
    @PostMapping("/batch/{batchId}/produce")
    public ApiResponse<Pellet> produce(@PathVariable Long batchId,
                                       @RequestParam(required = false) BigDecimal outputTons) {
        return ApiResponse.success("制粒完成，环模累计产量已累加", pelletService.produce(batchId, outputTons));
    }
}
