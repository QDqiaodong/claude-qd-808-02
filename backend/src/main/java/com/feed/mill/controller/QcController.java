package com.feed.mill.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.feed.mill.common.ApiResponse;
import com.feed.mill.domain.QcRecheck;
import com.feed.mill.domain.QcSample;
import com.feed.mill.dto.QcFailDistVO;
import com.feed.mill.dto.QcJudgeCommand;
import com.feed.mill.dto.RecheckConcludeCommand;
import com.feed.mill.dto.RecheckCreateCommand;
import com.feed.mill.dto.RecheckMeasureCommand;
import com.feed.mill.service.QcService;
import com.feed.mill.service.RecheckService;

import jakarta.validation.Valid;

/**
 * 质检与判定：检验项实测值 vs 标准区间，结论盖章。
 * 检验项是 @SortNatural 有序 SortedSet。
 */
@RestController
@RequestMapping("/api/qc")
public class QcController {

    private final QcService qcService;
    private final RecheckService recheckService;

    public QcController(QcService qcService, RecheckService recheckService) {
        this.qcService = qcService;
        this.recheckService = recheckService;
    }

    /** 抽样批次列表（含检验项） */
    @GetMapping("/samples")
    public ApiResponse<List<QcSample>> samples() {
        return ApiResponse.success(qcService.samples());
    }

    @GetMapping("/samples/{id}")
    public ApiResponse<QcSample> sample(@PathVariable Long id) {
        return ApiResponse.success(qcService.sample(id));
    }

    /** 判定结论：霉菌毒素超标只能判退货 */
    @PostMapping("/samples/{id}/judge")
    public ApiResponse<QcSample> judge(@PathVariable Long id, @Valid @RequestBody QcJudgeCommand command) {
        return ApiResponse.success("判定已保存", qcService.judge(id, command));
    }

    /** 复检单列表（新检验值与初检原始记录分开存放） */
    @GetMapping("/rechecks")
    public ApiResponse<List<QcRecheck>> rechecks() {
        return ApiResponse.success(recheckService.list());
    }

    /** 发起复检：仅初检结论为退货的批次可发起，一批次一张进行中 */
    @PostMapping("/samples/{id}/rechecks")
    public ApiResponse<QcRecheck> createRecheck(@PathVariable Long id,
                                                @Valid @RequestBody RecheckCreateCommand command) {
        return ApiResponse.success("复检单已发起", recheckService.create(id, command));
    }

    /** 复核员录入复检检验值（复核员 ≠ 初检判定人，初检记录不改写） */
    @PostMapping("/rechecks/{id}/measure")
    public ApiResponse<QcRecheck> measure(@PathVariable Long id,
                                          @Valid @RequestBody RecheckMeasureCommand command) {
        return ApiResponse.success("复检检验值已保存", recheckService.measure(id, command));
    }

    /** 提交最终结论：原子收口，并发/重复提交只有第一个生效 */
    @PostMapping("/rechecks/{id}/conclude")
    public ApiResponse<QcRecheck> conclude(@PathVariable Long id,
                                           @Valid @RequestBody RecheckConcludeCommand command) {
        return ApiResponse.success("复检结论已生效", recheckService.conclude(id, command));
    }

    /** 质检不合格项分布（手写 JPQL） */
    @GetMapping("/stats/fail-distribution")
    public ApiResponse<List<QcFailDistVO>> failDistribution() {
        return ApiResponse.success(qcService.failDistribution());
    }

    /** 结论分布：合格/降级/退货 */
    @GetMapping("/stats/conclusion")
    public ApiResponse<Map<String, Long>> conclusionStats() {
        return ApiResponse.success(qcService.conclusionStats());
    }
}
