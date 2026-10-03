package com.feed.mill.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.feed.mill.common.BizException;
import com.feed.mill.domain.Batch;
import com.feed.mill.domain.FeedingItem;
import com.feed.mill.dto.BatchFeedErrorVO;
import com.feed.mill.dto.FeedingCommand;
import com.feed.mill.repository.BatchRepository;
import com.feed.mill.repository.FeedingItemRepository;
import com.feed.mill.service.MaterialService;

/**
 * 生产批次与投料：按投料口顺序称量、确认投料，误差超 ±2% 拦截并记录异常。
 * 投料明细是 {@code @SortComparator} 的 SortedSet，永远按投料顺序排列。
 */
@Service
public class BatchService {

    /** 允许的投料误差 ±2% */
    public static final BigDecimal TOLERANCE_RATE = new BigDecimal("2.00");

    private final BatchRepository batchRepository;
    private final FeedingItemRepository feedingItemRepository;
    private final MaterialService materialService;

    public BatchService(BatchRepository batchRepository,
                        FeedingItemRepository feedingItemRepository,
                        MaterialService materialService) {
        this.batchRepository = batchRepository;
        this.feedingItemRepository = feedingItemRepository;
        this.materialService = materialService;
    }

    @Transactional(readOnly = true)
    public List<Batch> list() {
        return batchRepository.findAllOrdered();
    }

    @Transactional(readOnly = true)
    public Batch detail(Long id) {
        return batchRepository.findById(id)
                .orElseThrow(() -> new BizException("生产批次不存在：id=" + id));
    }

    /**
     * 投料动作：WEIGH 开始称量，CANCEL 取消称量，CONFIRM 确认实际投料量。
     */
    @Transactional
    public Batch feed(Long batchId, FeedingCommand command) {
        Batch batch = detail(batchId);
        FeedingItem item = batch.getFeedings().stream()
                .filter(f -> f.getFeedSeq().equals(command.getFeedSeq()))
                .findFirst()
                .orElseThrow(() -> new BizException("批次 " + batch.getBatchNo() + " 没有第 "
                        + command.getFeedSeq() + " 号投料口"));

        String action = command.getAction() == null ? "WEIGH" : command.getAction().toUpperCase();
        if ("WEIGH".equals(action)) {
            item.setStatus("WEIGHING");
            batchRepository.save(batch);
            return batch;
        }
        if ("CANCEL".equals(action)) {
            if ("WEIGHING".equals(item.getStatus())) {
                boolean restoringAbnormal = item.getActualKg() != null || item.getAbnormalNote() != null;
                item.setStatus(restoringAbnormal ? "ABNORMAL" : "PENDING");
                batchRepository.save(batch);
            }
            return batch;
        }
        if (!"CONFIRM".equals(action)) {
            throw new BizException("不支持的投料动作：" + command.getAction());
        }

        if ("DONE".equals(item.getStatus())) {
            throw new BizException(item.getPortNo() + " 投料口已完成投料，请勿重复投料");
        }
        checkPrevious(batch, item);

        BigDecimal actual = command.getActualKg();
        if (actual == null) {
            throw new BizException("请填写实际称重值");
        }
        BigDecimal planned = nz(item.getPlannedKg());
        if (planned.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("计划投料量异常，无法计算误差");
        }
        BigDecimal deviation = actual.subtract(planned)
                .multiply(BigDecimal.valueOf(100))
                .divide(planned, 2, RoundingMode.HALF_UP);
        item.setActualKg(actual);
        item.setDeviationRate(deviation);
        item.setWeighedAt(LocalDateTime.now());

        if (deviation.abs().compareTo(TOLERANCE_RATE) > 0) {
            item.setStatus("ABNORMAL");
            item.setAbnormalNote("实际称重 " + strip(actual) + "kg，与计划 " + strip(planned) + "kg 偏差 "
                    + strip(deviation) + "%，超出 ±" + strip(TOLERANCE_RATE) + "% 阈值，已判定异常待复核");
            batchRepository.save(batch);
            throw new BizException(item.getPortNo() + " " + item.getMaterial().getName()
                    + " 实际投料 " + strip(actual) + "kg，与计划 " + strip(planned) + "kg 偏差 "
                    + strip(deviation) + "%，超过 ±2% 已拦截并记录异常，请复核后重新称量");
        }

        materialService.deductStock(item.getMaterial().getId(), actual);
        item.setStatus("DONE");
        item.setAbnormalNote(null);

        boolean allDone = batch.getFeedings().stream().allMatch(f -> "DONE".equals(f.getStatus()));
        if (allDone) {
            batch.setStatus("PELLETING");
        } else {
            batch.setStatus("FEEDING");
        }
        return batchRepository.save(batch);
    }

    /** 必须按投料顺序投料：上一道未完成不允许投下一道 */
    private void checkPrevious(Batch batch, FeedingItem item) {
        for (FeedingItem other : batch.getFeedings()) {
            if (other.getFeedSeq() < item.getFeedSeq() && !"DONE".equals(other.getStatus())) {
                throw new BizException("第 " + other.getFeedSeq() + " 号投料口（" + other.getMaterial().getName()
                        + "）尚未完成，不能开始第 " + item.getFeedSeq() + " 道投料");
            }
        }
    }

    /** 按批次统计投料误差率（手写 JPQL） */
    @Transactional(readOnly = true)
    public List<BatchFeedErrorVO> feedErrorStats() {
        List<BatchFeedErrorVO> result = new ArrayList<>();
        for (Object[] row : feedingItemRepository.batchFeedErrorStats()) {
            BatchFeedErrorVO vo = new BatchFeedErrorVO();
            vo.setBatchId(toLong(row[0]));
            vo.setBatchNo((String) row[1]);
            vo.setItemCount(toLong(row[2]));
            vo.setAvgDeviationRate(scale2(row[3]));
            vo.setMaxDeviationRate(scale2(row[4]));
            vo.setAbnormalCount(toLong(row[5]));
            BigDecimal max = vo.getMaxDeviationRate();
            if (max.compareTo(TOLERANCE_RATE) > 0) {
                vo.setRiskLevel("失控");
            } else if (max.compareTo(new BigDecimal("1.00")) > 0) {
                vo.setRiskLevel("关注");
            } else {
                vo.setRiskLevel("稳定");
            }
            result.add(vo);
        }
        return result;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String strip(BigDecimal value) {
        return nz(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static BigDecimal scale2(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal v = value instanceof BigDecimal b ? b : new BigDecimal(value.toString());
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static Long toLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }
}
