package com.feed.mill.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.feed.mill.common.ApiResponse;
import com.feed.mill.domain.Batch;
import com.feed.mill.dto.BatchFeedErrorVO;
import com.feed.mill.dto.FeedingCommand;
import com.feed.mill.service.BatchService;

import jakarta.validation.Valid;

/**
 * 生产批次与投料。
 * GET /api/batches/{id} 返回的投料明细是 @SortComparator 有序 SortedSet（按投料口顺序）。
 */
@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final BatchService batchService;

    public BatchController(BatchService batchService) {
        this.batchService = batchService;
    }

    /** 批次列表 */
    @GetMapping
    public ApiResponse<List<Batch>> list() {
        return ApiResponse.success(batchService.list());
    }

    /** 批次详情：投料明细按投料顺序排好 */
    @GetMapping("/{id}")
    public ApiResponse<Batch> detail(@PathVariable Long id) {
        return ApiResponse.success(batchService.detail(id));
    }

    /** 开始称量 / 确认投料 */
    @PostMapping("/{id}/feeding")
    public ApiResponse<Batch> feed(@PathVariable Long id, @Valid @RequestBody FeedingCommand command) {
        Batch batch = batchService.feed(id, command);
        String action = command.getAction() == null ? "WEIGH" : command.getAction().toUpperCase();
        String message = switch (action) {
            case "CONFIRM" -> "投料已确认并计入批次";
            case "CANCEL" -> "称量已取消";
            default -> "已进入称量状态";
        };
        return ApiResponse.success(message, batch);
    }

    /** 按批次统计投料误差率（手写 JPQL） */
    @GetMapping("/stats/feed-error")
    public ApiResponse<List<BatchFeedErrorVO>> feedErrorStats() {
        return ApiResponse.success(batchService.feedErrorStats());
    }
}
