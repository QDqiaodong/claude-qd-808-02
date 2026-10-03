package com.feed.mill.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.feed.mill.common.BizException;
import com.feed.mill.domain.Batch;
import com.feed.mill.domain.QcItem;
import com.feed.mill.domain.QcRecheck;
import com.feed.mill.domain.QcRecheckItem;
import com.feed.mill.domain.QcSample;
import com.feed.mill.dto.RecheckConcludeCommand;
import com.feed.mill.dto.RecheckCreateCommand;
import com.feed.mill.dto.RecheckMeasureCommand;
import com.feed.mill.repository.BatchRepository;
import com.feed.mill.repository.QcRecheckRepository;
import com.feed.mill.repository.QcSampleRepository;

/**
 * 退货批次复检放行闭环。
 * <ul>
 *   <li>只有初检结论为 REJECT 的批次可以发起复检，发起人、原因、待复核状态全程留痕；</li>
 *   <li>复核员必须不同于初检判定人；新检验值写入 qc_recheck_item，初检 qc_sample/qc_item 永不改写；</li>
 *   <li>最终结论：霉菌毒素仍超标必须继续退货；全部项目恢复标准范围才允许放行；中间情形判降级并给明确去向；</li>
 *   <li>一批次同一时刻只有一张进行中复检单（唯一索引），最终结论用条件更新原子收口，后到请求只能看到已完成结果。</li>
 * </ul>
 */
@Service
public class RecheckService {

    /** 最终结论：放行 */
    public static final String RELEASE = "RELEASE";
    /** 最终结论：降级 */
    public static final String DOWNGRADE = "DOWNGRADE";
    /** 最终结论：继续退货 */
    public static final String REJECT = "REJECT";

    private static final Set<String> FINAL_CONCLUSIONS = Set.of(RELEASE, DOWNGRADE, REJECT);

    /** 各结论的默认去向（降级情形给出明确去向） */
    private static final Map<String, String> DEFAULT_DISPOSITION = Map.of(
            RELEASE, "成品仓入库",
            DOWNGRADE, "降级销售",
            REJECT, "回机处理");

    private final QcRecheckRepository recheckRepository;
    private final QcSampleRepository sampleRepository;
    private final BatchRepository batchRepository;
    private final PlatformTransactionManager transactionManager;

    public RecheckService(QcRecheckRepository recheckRepository,
                          QcSampleRepository sampleRepository,
                          BatchRepository batchRepository,
                          PlatformTransactionManager transactionManager) {
        this.recheckRepository = recheckRepository;
        this.sampleRepository = sampleRepository;
        this.batchRepository = batchRepository;
        this.transactionManager = transactionManager;
    }

    @Transactional(readOnly = true)
    public List<QcRecheck> list() {
        return recheckRepository.findAllByOrderByIdDesc();
    }

    @Transactional(readOnly = true)
    public QcRecheck recheck(Long id) {
        return recheckRepository.findById(id)
                .orElseThrow(() -> new BizException("复检单不存在：id=" + id));
    }

    /**
     * 发起复检：仅初检结论为退货的批次可发起；一批次同一时刻只允许一张进行中的复检单。
     */
    @Transactional
    public QcRecheck create(Long sampleId, RecheckCreateCommand command) {
        QcSample sample = sampleRepository.findById(sampleId)
                .orElseThrow(() -> new BizException("质检抽样单不存在：id=" + sampleId));
        if (!REJECT.equals(sample.getConclusion())) {
            throw new BizException("只有初检结论为退货的批次才能发起复检，抽样单 " + sample.getSampleNo()
                    + " 的初检结论为「" + conclusionName(sample.getConclusion()) + "」，不能进入复检流程");
        }
        Batch batch = sample.getBatch();
        recheckRepository.findFirstByBatch_IdAndActiveFlagOrderByIdDesc(batch.getId(), 1)
                .ifPresent(existing -> {
                    throw new BizException("批次 " + batch.getBatchNo() + " 已存在进行中的复检单 "
                            + existing.getRecheckNo() + "（状态：" + statusName(existing.getStatus())
                            + "），同一批次同一时刻只能有一张复检单");
                });

        long seq = recheckRepository.countBySample_Id(sampleId) + 1;
        QcRecheck recheck = new QcRecheck();
        recheck.setRecheckNo("RC-" + sample.getSampleNo().replaceFirst("^QC-", "") + "-" + seq);
        recheck.setSample(sample);
        recheck.setBatch(batch);
        recheck.setInitiator(command.getInitiator().trim());
        recheck.setReason(command.getReason().trim());
        recheck.setStatus("PENDING");
        recheck.setActiveFlag(1);
        recheck.setCreatedAt(LocalDateTime.now());

        QcRecheck saved;
        try {
            // 并发兜底：uk_recheck_active 保证一批一单，撞唯一索引即已有进行中的单
            saved = recheckRepository.saveAndFlush(recheck);
        } catch (DataIntegrityViolationException e) {
            throw new BizException("批次 " + batch.getBatchNo() + " 已存在进行中的复检单，请勿重复发起");
        }

        // 批次状态回写：进入复检
        batch.setStatus("RECHECK");
        batchRepository.save(batch);
        return saved;
    }

    /**
     * 复核员录入复检检验值。
     * 复核员必须不同于初检判定人；检验值写入复检单自己的明细，初检记录保持原样。
     */
    @Transactional
    public QcRecheck measure(Long recheckId, RecheckMeasureCommand command) {
        QcRecheck recheck = recheck(recheckId);
        ensureActive(recheck);

        String reviewer = command.getReviewer().trim();
        String firstInspector = recheck.getSample().getSampler();
        if (reviewer.equals(firstInspector)) {
            throw new BizException("复核员「" + reviewer + "」与初检判定人相同，复检必须由不同的复核员执行");
        }
        if (recheck.getReviewer() == null) {
            int claimed = recheckRepository.claimReviewer(recheck.getId(), reviewer);
            if (claimed == 0) {
                QcRecheck current = recheck(recheckId);
                ensureActive(current);
                throw new BizException("本复检单已由「" + current.getReviewer() + "」接手复核，不能更换复核员");
            }
            recheck.setReviewer(reviewer);
        } else if (!recheck.getReviewer().equals(reviewer)) {
            throw new BizException("本复检单已由「" + recheck.getReviewer() + "」接手复核，不能更换复核员");
        }

        // 校验：复检值必须完整覆盖初检项目，且不允许多余项目
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        for (RecheckMeasureCommand.ItemValue v : command.getItems()) {
            values.put(v.getItemCode().toUpperCase(), v.getMeasuredValue());
        }
        for (QcItem origin : recheck.getSample().getItems()) {
            if (!values.containsKey(origin.getItemCode())) {
                throw new BizException("缺少检验项「" + origin.getItemName() + "」的复检实测值，复检必须完整覆盖初检项目");
            }
        }
        for (String code : values.keySet()) {
            boolean known = recheck.getSample().getItems().stream()
                    .anyMatch(i -> i.getItemCode().equals(code));
            if (!known) {
                throw new BizException("检验项 " + code + " 不在初检项目中，不能录入");
            }
        }

        // 重建复检明细（复检单自己的数据，进行中允许修正；初检 qc_item 不动）
        recheck.getItems().clear();
        for (QcItem origin : recheck.getSample().getItems()) {
            BigDecimal value = values.get(origin.getItemCode());
            QcRecheckItem item = new QcRecheckItem();
            item.setRecheck(recheck);
            item.setItemCode(origin.getItemCode());
            item.setItemName(origin.getItemName());
            item.setCheckOrder(origin.getCheckOrder());
            item.setUnit(origin.getUnit());
            item.setStdLower(origin.getStdLower());
            item.setStdUpper(origin.getStdUpper());
            item.setMeasuredValue(value);
            item.setQualified(qualified(value, origin.getStdLower(), origin.getStdUpper()));
            recheck.getItems().add(item);
        }
        recheck.setStatus("MEASURING");
        return recheckRepository.save(recheck);
    }

    /**
     * 提交最终结论（原子收口）。
     * 霉菌毒素仍超标必须继续退货；全部项目恢复标准范围才允许放行；中间情形判降级并给明确去向。
     * 并发或重复提交时只有第一个请求生效，后到请求看到已完成结果而不能覆盖。
     */
    @Transactional
    public QcRecheck conclude(Long recheckId, RecheckConcludeCommand command) {
        QcRecheck recheck = recheck(recheckId);
        ensureActive(recheck);

        String reviewer = command.getReviewer().trim();
        if (recheck.getReviewer() == null || recheck.getItems().isEmpty()) {
            throw new BizException("请先由复核员录入复检检验值，再提交最终结论");
        }
        if (!recheck.getReviewer().equals(reviewer)) {
            throw new BizException("最终结论必须由复核员「" + recheck.getReviewer() + "」本人提交");
        }

        String conclusion = command.getConclusion() == null ? "" : command.getConclusion().toUpperCase();
        if (!FINAL_CONCLUSIONS.contains(conclusion)) {
            throw new BizException("复检结论只能是 放行(RELEASE) / 降级(DOWNGRADE) / 继续退货(REJECT)");
        }

        List<QcRecheckItem> failed = new ArrayList<>();
        QcRecheckItem mycotoxin = null;
        for (QcRecheckItem item : recheck.getItems()) {
            if (item.getMeasuredValue() == null) {
                throw new BizException("检验项「" + item.getItemName() + "」还没有复检实测值，请先完成录入");
            }
            if (Boolean.FALSE.equals(item.getQualified())) {
                failed.add(item);
                if (QcService.MYCOTOXIN.equals(item.getItemCode())) {
                    mycotoxin = item;
                }
            }
        }

        if (mycotoxin != null && !REJECT.equals(conclusion)) {
            throw new BizException("复检霉菌毒素实测 " + strip(mycotoxin.getMeasuredValue()) + mycotoxin.getUnit()
                    + " 仍超出标准上限 " + strip(mycotoxin.getStdUpper()) + mycotoxin.getUnit()
                    + "，按规程必须继续退货，不允许放行或降级");
        }
        if (failed.isEmpty() && !RELEASE.equals(conclusion)) {
            throw new BizException("复检全部项目已恢复到标准范围内，应当放行，不能再判降级或退货");
        }
        if (RELEASE.equals(conclusion) && !failed.isEmpty()) {
            QcRecheckItem first = failed.get(0);
            throw new BizException(first.getItemName() + "复检实测 " + strip(first.getMeasuredValue()) + first.getUnit()
                    + " 仍未回到标准区间 [" + strip(first.getStdLower()) + ", " + strip(first.getStdUpper()) + "]"
                    + first.getUnit() + "，不允许放行，请判降级或继续退货");
        }

        String disposition = command.getDisposition() == null || command.getDisposition().isBlank()
                ? DEFAULT_DISPOSITION.get(conclusion)
                : command.getDisposition().trim();
        LocalDateTime now = LocalDateTime.now();

        // 原子收口：只有仍处进行中的单才能被盖上最终结论
        int closed = recheckRepository.closeIfActive(recheck.getId(), conclusion, disposition, now,
                command.getRemark());
        if (closed == 0) {
            // 另起事务读已提交结果：后到请求必须看到先完成请求的结论，而不是本事务的旧快照
            QcRecheck current = committedRecheck(recheckId);
            throw new BizException("复检单 " + current.getRecheckNo() + " 已由「" + current.getReviewer()
                    + "」完成，最终结论「" + finalName(current.getFinalConclusion()) + "」，后到请求不能覆盖");
        }

        // 同步内存态（closeIfActive 是 bulk update，不经过持久化上下文）
        recheck.setStatus("DONE");
        recheck.setActiveFlag(null);
        recheck.setFinalConclusion(conclusion);
        recheck.setFinalDisposition(disposition);
        recheck.setReviewedAt(now);
        recheck.setRemark(command.getRemark());

        // 回写批次状态：复检闭环结束，按最终去向处置完成
        Batch batch = recheck.getBatch();
        batch.setStatus("DONE");
        batchRepository.save(batch);
        return recheck;
    }

    /** 复检最终结论映射到概览统计口径：放行 = 合格 */
    public static String statsConclusion(String finalConclusion) {
        return RELEASE.equals(finalConclusion) ? "PASS" : finalConclusion;
    }

    public static String finalName(String conclusion) {
        if (conclusion == null) {
            return "-";
        }
        return switch (conclusion) {
            case RELEASE -> "放行";
            case DOWNGRADE -> "降级";
            case REJECT -> "继续退货";
            default -> conclusion;
        };
    }

    /** 在新事务（REQUIRES_NEW）里读取已提交的复检单，绕过当前事务的可重复读快照 */
    private QcRecheck committedRecheck(Long id) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(true);
        return template.execute(status -> recheckRepository.findById(id)
                .orElseThrow(() -> new BizException("复检单不存在：id=" + id)));
    }

    private void ensureActive(QcRecheck recheck) {
        if (!Integer.valueOf(1).equals(recheck.getActiveFlag()) || "DONE".equals(recheck.getStatus())) {
            throw new BizException("复检单 " + recheck.getRecheckNo() + " 已完成，最终结论「"
                    + finalName(recheck.getFinalConclusion()) + "」，不能覆盖或改写");
        }
    }

    private static boolean qualified(BigDecimal value, BigDecimal lower, BigDecimal upper) {
        if (lower != null && value.compareTo(lower) < 0) {
            return false;
        }
        return upper == null || value.compareTo(upper) <= 0;
    }

    private static String conclusionName(String conclusion) {
        if (conclusion == null) {
            return "待判定";
        }
        return switch (conclusion) {
            case "PASS" -> "合格";
            case "DOWNGRADE" -> "降级";
            case "REJECT" -> "退货";
            default -> conclusion;
        };
    }

    private static String statusName(String status) {
        if (status == null) {
            return "-";
        }
        return switch (status) {
            case "PENDING" -> "待复核";
            case "MEASURING" -> "复检中";
            case "DONE" -> "已完成";
            default -> status;
        };
    }

    private static String strip(BigDecimal value) {
        return value == null ? "-" : value.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
