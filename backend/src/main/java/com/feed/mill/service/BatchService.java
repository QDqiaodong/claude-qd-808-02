package com.feed.mill.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.feed.mill.common.BizException;
import com.feed.mill.domain.Batch;
import com.feed.mill.domain.BatchMigration;
import com.feed.mill.domain.FeedingItem;
import com.feed.mill.domain.Recipe;
import com.feed.mill.domain.RecipeItem;
import com.feed.mill.dto.BatchFeedErrorVO;
import com.feed.mill.dto.FeedingCommand;
import com.feed.mill.dto.MigrationCommand;
import com.feed.mill.dto.MigrationPreviewVO;
import com.feed.mill.repository.BatchMigrationRepository;
import com.feed.mill.repository.BatchRepository;
import com.feed.mill.repository.FeedingItemRepository;
import com.feed.mill.repository.RecipeRepository;
import com.feed.mill.service.MaterialService;

/**
 * 生产批次与投料：按投料口顺序称量、确认投料，误差超 ±2% 拦截并记录异常。
 * 投料明细是 {@code @SortComparator} 的 SortedSet，永远按投料顺序排列。
 * <p>
 * 配方迁移：发布新修订后，计划中批次默认冻结在原版本快照（不偷改），操作员看清差异后
 * 选择「迁移到新版本」（重新生成投料明细、计划用量与成本）或「确认冻结」。
 * 已开始投料/制粒/质检的批次必须保持原版本与原明细，服务端硬校验 + 条件更新双重拦截；
 * 迁移/冻结用条件更新原子收口，多人同时操作同一批次只有第一个生效，选择记录全程留痕。
 */
@Service
public class BatchService {

    /** 允许的投料误差 ±2% */
    public static final BigDecimal TOLERANCE_RATE = new BigDecimal("2.00");

    private final BatchRepository batchRepository;
    private final FeedingItemRepository feedingItemRepository;
    private final RecipeRepository recipeRepository;
    private final BatchMigrationRepository migrationRepository;
    private final MaterialService materialService;
    private final PlatformTransactionManager transactionManager;

    public BatchService(BatchRepository batchRepository,
                        FeedingItemRepository feedingItemRepository,
                        RecipeRepository recipeRepository,
                        BatchMigrationRepository migrationRepository,
                        MaterialService materialService,
                        PlatformTransactionManager transactionManager) {
        this.batchRepository = batchRepository;
        this.feedingItemRepository = feedingItemRepository;
        this.recipeRepository = recipeRepository;
        this.migrationRepository = migrationRepository;
        this.materialService = materialService;
        this.transactionManager = transactionManager;
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
     * 迁移影响预览：新旧版本明细对照、计划用量与成本变化，让操作员看清影响后再确认。
     */
    @Transactional(readOnly = true)
    public MigrationPreviewVO migrationPreview(Long batchId) {
        Batch batch = detail(batchId);
        Recipe current = batch.getRecipe();
        Recipe latest = latestRevision(current.getRecipeCode());

        MigrationPreviewVO vo = new MigrationPreviewVO();
        vo.setBatchId(batch.getId());
        vo.setBatchNo(batch.getBatchNo());
        vo.setBatchStatus(batch.getStatus());
        vo.setMigrationStatus(batch.getMigrationStatus());
        vo.setRecipeCode(current.getRecipeCode());
        vo.setFromRecipeId(current.getId());
        vo.setFromRevisionNo(current.getRevisionNo());
        vo.setToRecipeId(latest.getId());
        vo.setToRevisionNo(latest.getRevisionNo());
        vo.setPlannedTons(batch.getPlannedTons());
        vo.setCostPerTonBefore(current.getCostPerTon());
        vo.setCostPerTonAfter(latest.getCostPerTon());
        vo.setCostBefore(batchCost(current, batch));
        vo.setCostAfter(batchCost(latest, batch));

        boolean outdated = !latest.getId().equals(current.getId());
        boolean planned = "PLANNED".equals(batch.getStatus());
        boolean started = batch.getFeedings().stream().anyMatch(f -> !"PENDING".equals(f.getStatus()));
        vo.setCanMigrate(outdated && planned && !started);
        if (!outdated) {
            vo.setBlockReason("批次已采用最新生效版本 V" + latest.getRevisionNo() + "，无需迁移");
        } else if (!planned) {
            vo.setBlockReason("批次已开始" + stageName(batch.getStatus())
                    + "，必须保持原版本 V" + current.getRevisionNo() + " 与原投料明细，不允许迁移");
        } else if (started) {
            vo.setBlockReason("批次已有称量/投料动作，必须保持原版本 V" + current.getRevisionNo()
                    + " 与原投料明细，不允许迁移");
        }

        // 明细对照：按新版本顺序展开，被移除的原料附在末尾
        Map<Long, RecipeItem> before = new LinkedHashMap<>();
        for (RecipeItem item : current.getItems()) {
            before.put(item.getMaterial().getId(), item);
        }
        List<MigrationPreviewVO.ItemDiff> items = new ArrayList<>();
        for (RecipeItem newItem : latest.getItems()) {
            RecipeItem oldItem = before.remove(newItem.getMaterial().getId());
            items.add(diffRow(oldItem, newItem, batch));
        }
        for (RecipeItem removed : before.values()) {
            items.add(diffRow(removed, null, batch));
        }
        vo.setItems(items);
        return vo;
    }

    /**
     * 批次配方迁移选择（原子收口）。
     * MIGRATE：切到最新生效版本，投料明细/计划用量全部按新版本重新生成；
     * FREEZE：确认冻结在当前版本快照。两种选择都写迁移记录。
     * 并发或重复提交只有第一个请求生效，后到请求看到已提交结果而不能覆盖。
     */
    @Transactional
    public Batch migrate(Long batchId, MigrationCommand command) {
        String action = command.getAction() == null ? "" : command.getAction().toUpperCase();
        if (!BatchMigration.MIGRATE.equals(action) && !BatchMigration.FREEZE.equals(action)) {
            throw new BizException("操作类型只能是 MIGRATE（迁移到新版本）或 FREEZE（冻结在原版本）");
        }
        Batch batch = detail(batchId);
        Recipe current = batch.getRecipe();
        Recipe latest = latestRevision(current.getRecipeCode());

        if (latest.getId().equals(current.getId())) {
            throw new BizException("批次 " + batch.getBatchNo() + " 已采用最新生效版本 V"
                    + latest.getRevisionNo() + "，无需迁移");
        }
        if (!"PLANNED".equals(batch.getStatus())) {
            throw new BizException("批次 " + batch.getBatchNo() + " 已开始" + stageName(batch.getStatus())
                    + "，必须保持原版本 V" + current.getRevisionNo() + " 与原投料明细，不允许迁移");
        }

        BigDecimal costBefore = batchCost(current, batch);
        BigDecimal costAfter = batchCost(latest, batch);
        LocalDateTime now = LocalDateTime.now();

        if (BatchMigration.MIGRATE.equals(action)) {
            // 预检：已有称量/投料动作的批次视同「已开始投料」，必须保持原版本与原明细
            boolean started = batch.getFeedings().stream().anyMatch(f -> !"PENDING".equals(f.getStatus()));
            if (started) {
                throw new BizException("批次 " + batch.getBatchNo() + " 已有称量/投料动作，"
                        + "必须保持原版本 V" + current.getRevisionNo() + " 与原投料明细，不允许迁移");
            }
            // 原子收口：只有仍是 PLANNED、仍引用原版本、且明细全部未动的批次才能被迁移（占位即加锁）
            int updated = batchRepository.migrateIfPlanned(batch.getId(), current.getId(), latest);
            if (updated == 0) {
                throw alreadyHandled(batchId);
            }
            // 同步内存态（bulk update 不经过持久化上下文）
            batch.setRecipe(latest);
            batch.setMigrationStatus("MIGRATED");
            // 重新生成投料明细与计划用量：旧明细全部未投（PLANNED 批次），按新版本重建
            batch.getFeedings().clear();
            int seq = 1;
            for (RecipeItem item : latest.getItems()) {
                FeedingItem feeding = new FeedingItem();
                feeding.setBatch(batch);
                feeding.setFeedSeq(seq);
                feeding.setPortNo("P" + seq);
                feeding.setMaterial(item.getMaterial());
                feeding.setPlannedKg(plannedKg(batch.getPlannedTons(), item.getRatioPct()));
                feeding.setStatus("PENDING");
                batch.getFeedings().add(feeding);
                seq++;
            }
            batchRepository.save(batch);
            saveMigration(batch, BatchMigration.MIGRATE, current, latest, costBefore, costAfter, command, now);
            return batch;
        }

        // FREEZE：确认冻结在当前版本快照（原子收口，与迁移互斥）
        int updated = batchRepository.freezeIfPlanned(batch.getId(), current.getId());
        if (updated == 0) {
            throw alreadyHandled(batchId);
        }
        batch.setMigrationStatus("FROZEN");
        batchRepository.save(batch);
        saveMigration(batch, BatchMigration.FREEZE, current, latest, costBefore, costAfter, command, now);
        return batch;
    }

    /** 批次的迁移/冻结记录（可追溯最终采用的配方版本） */
    @Transactional(readOnly = true)
    public List<BatchMigration> migrations(Long batchId) {
        return migrationRepository.findByBatchIdOrderByIdDesc(batchId);
    }

    /** 全部迁移/冻结记录，新记录在前 */
    @Transactional(readOnly = true)
    public List<BatchMigration> allMigrations() {
        return migrationRepository.findAllByOrderByIdDesc();
    }

    /** 后到请求看到的已提交结果：另起事务读，绕过当前事务的可重复读快照 */
    private BizException alreadyHandled(Long batchId) {
        Batch committed = committedBatch(batchId);
        String version = committed.getRecipe().getRecipeCode() + " V" + committed.getRecipe().getRevisionNo();
        if (!"PLANNED".equals(committed.getStatus())) {
            return new BizException("批次 " + committed.getBatchNo() + " 已开始" + stageName(committed.getStatus())
                    + "，保持 " + version + " 与原投料明细，后到请求不能覆盖");
        }
        boolean started = committed.getFeedings().stream().anyMatch(f -> !"PENDING".equals(f.getStatus()));
        if (started) {
            return new BizException("批次 " + committed.getBatchNo() + " 已有称量/投料动作，保持 "
                    + version + " 与原投料明细，后到请求不能覆盖");
        }
        return new BizException("批次 " + committed.getBatchNo() + " 已被他人处理：当前采用 " + version
                + "（" + migrationName(committed.getMigrationStatus()) + "），后到请求不能覆盖");
    }

    private void saveMigration(Batch batch, String action, Recipe from, Recipe to,
                               BigDecimal costBefore, BigDecimal costAfter,
                               MigrationCommand command, LocalDateTime now) {
        BatchMigration migration = new BatchMigration();
        migration.setBatchId(batch.getId());
        migration.setBatchNo(batch.getBatchNo());
        migration.setAction(action);
        migration.setRecipeCode(from.getRecipeCode());
        migration.setFromRecipeId(from.getId());
        migration.setFromRevisionNo(from.getRevisionNo());
        migration.setToRecipeId(to.getId());
        migration.setToRevisionNo(to.getRevisionNo());
        migration.setPlannedTons(batch.getPlannedTons());
        migration.setCostBefore(costBefore);
        migration.setCostAfter(costAfter);
        migration.setOperatorName(command.getOperatorName().trim());
        migration.setReason(command.getReason() == null || command.getReason().isBlank()
                ? null : command.getReason().trim());
        migration.setCreatedAt(now);
        migrationRepository.save(migration);
    }

    /** 当前生效版本（active_flag=1 唯一） */
    private Recipe latestRevision(String recipeCode) {
        return recipeRepository.findFirstByRecipeCodeAndActiveFlag(recipeCode, 1)
                .orElseThrow(() -> new BizException("配方 " + recipeCode + " 没有生效版本"));
    }

    private MigrationPreviewVO.ItemDiff diffRow(RecipeItem oldItem, RecipeItem newItem, Batch batch) {
        MigrationPreviewVO.ItemDiff row = new MigrationPreviewVO.ItemDiff();
        RecipeItem ref = newItem != null ? newItem : oldItem;
        row.setMaterialCode(ref.getMaterial().getMaterialCode());
        row.setMaterialName(ref.getMaterial().getName());
        BigDecimal ratioBefore = oldItem == null ? null : nz(oldItem.getRatioPct());
        BigDecimal ratioAfter = newItem == null ? null : nz(newItem.getRatioPct());
        row.setRatioBefore(ratioBefore);
        row.setRatioAfter(ratioAfter);
        row.setPlannedKgBefore(ratioBefore == null ? null : plannedKg(batch.getPlannedTons(), ratioBefore));
        row.setPlannedKgAfter(ratioAfter == null ? null : plannedKg(batch.getPlannedTons(), ratioAfter));
        if (oldItem == null) {
            row.setChange("ADDED");
        } else if (newItem == null) {
            row.setChange("REMOVED");
        } else {
            row.setChange(nz(ratioBefore).compareTo(nz(ratioAfter)) == 0 ? "SAME" : "CHANGED");
        }
        return row;
    }

    /** 计划投料量 kg = 计划产量吨 × 1000 × 占比% ÷ 100 */
    private static BigDecimal plannedKg(BigDecimal plannedTons, BigDecimal ratioPct) {
        return nz(plannedTons).multiply(BigDecimal.valueOf(1000)).multiply(nz(ratioPct))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    /** 批次计划成本 = 吨成本 × 计划产量吨 */
    private static BigDecimal batchCost(Recipe recipe, Batch batch) {
        return nz(recipe.getCostPerTon()).multiply(nz(batch.getPlannedTons()))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** 在新事务（REQUIRES_NEW）里读取已提交的批次，绕过当前事务的可重复读快照 */
    private Batch committedBatch(Long id) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(true);
        return template.execute(status -> batchRepository.findById(id)
                .orElseThrow(() -> new BizException("生产批次不存在：id=" + id)));
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

    private static String stageName(String status) {
        if (status == null) {
            return "生产";
        }
        return switch (status) {
            case "FEEDING" -> "投料";
            case "PELLETING" -> "制粒";
            case "QC" -> "质检";
            case "RECHECK" -> "复检";
            case "DONE" -> "完工";
            default -> "生产";
        };
    }

    private static String migrationName(String migrationStatus) {
        if (migrationStatus == null) {
            return "-";
        }
        return switch (migrationStatus) {
            case "PENDING" -> "待选择";
            case "FROZEN" -> "已冻结原版本";
            case "MIGRATED" -> "已迁移新版本";
            default -> "无待办";
        };
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
