package com.feed.mill.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.feed.mill.common.BizException;
import com.feed.mill.domain.Batch;
import com.feed.mill.domain.BatchRecipeMigration;
import com.feed.mill.domain.FeedingItem;
import com.feed.mill.domain.Recipe;
import com.feed.mill.domain.RecipeItem;
import com.feed.mill.dto.BatchRecipeMigrationCommand;
import com.feed.mill.repository.BatchRecipeMigrationRepository;
import com.feed.mill.repository.BatchRepository;
import com.feed.mill.repository.FeedingItemRepository;
import com.feed.mill.repository.RecipeRepository;

/**
 * 批次配方版本决策：新修订发布后默认继续使用旧快照；只有未投料的 PLANNED 批次可确认迁移。
 * 迁移会原子重算投料明细、计划用量和计划成本；已开工批次保持旧版本与原明细。
 */
@Service
public class BatchMigrationService {

    private final BatchRepository batchRepository;
    private final RecipeRepository recipeRepository;
    private final FeedingItemRepository feedingItemRepository;
    private final BatchRecipeMigrationRepository migrationRepository;

    public BatchMigrationService(BatchRepository batchRepository,
                                 RecipeRepository recipeRepository,
                                 FeedingItemRepository feedingItemRepository,
                                 BatchRecipeMigrationRepository migrationRepository) {
        this.batchRepository = batchRepository;
        this.recipeRepository = recipeRepository;
        this.feedingItemRepository = feedingItemRepository;
        this.migrationRepository = migrationRepository;
    }

    @Transactional(readOnly = true)
    public void enrich(Batch batch) {
        if (batch == null || batch.getRecipe() == null) {
            return;
        }
        Recipe current = recipeRepository
                .findByFamilyKeyAndCurrentFlag(batch.getRecipe().getFamilyKey(), 1)
                .orElse(null);
        batch.setCurrentRecipe(current);
        boolean available = current != null && !current.getId().equals(batch.getRecipe().getId());
        batch.setMigrationAvailable(available && canMigrate(batch) == null);
        batch.setMigrationBlockReason(migrationBlockReason(batch, current, available));
    }

    @Transactional(readOnly = true)
    public List<BatchRecipeMigration> migrations(Long batchId) {
        if (!batchRepository.existsById(batchId)) {
            throw new BizException("生产批次不存在：id=" + batchId);
        }
        return migrationRepository.findDetailByBatchId(batchId);
    }

    /**
     * 新修订发布后，所有尚未确认迁移的旧版本计划批次默认继续冻结旧快照。
     */
    @Transactional
    public void freezePlannedBatchesOnPublish(Recipe oldRecipe, Recipe newRecipe, String operatorName) {
        LocalDateTime now = LocalDateTime.now();
        for (Long batchId : batchRepository.lockPlannedByRecipe(oldRecipe.getId())) {
            Batch batch = batchRepository.findDetailById(batchId)
                    .orElse(null);
            if (batch == null) {
                continue;
            }
            boolean touched = batch.getFeedings().stream()
                    .anyMatch(f -> f.getActualKg() != null || !"PENDING".equals(f.getStatus()));
            String reason = touched ? "STARTED" : "DEFAULT";
            batch.setRecipeVersionLocked(true);
            batch.setVersionFreezeReason(reason);
            if (touched) {
                batch.setStatus("FEEDING");
            }
            record(batch, oldRecipe, oldRecipe, "FREEZE", operatorName,
                    "新修订 V" + newRecipe.getVersionNo() + " 发布，批次默认继续使用旧版本快照；"
                            + (touched ? "且已有投料动作，永久冻结" : "操作员可主动迁移"),
                    batch.getPlannedCost(), null);
            batch.setRecipeDecisionAt(now);
            batchRepository.save(batch);
        }
    }

    /**
     * 开始投料瞬间冻结批次。由投料开始称量/确认在同一事务内调用。
     */
    @Transactional
    public void freezeForFeeding(Batch batch, String operatorName) {
        if (batch == null || "STARTED".equals(batch.getVersionFreezeReason())
                || "KEEP".equals(batch.getVersionFreezeReason())) {
            return;
        }
        batch.setRecipeVersionLocked(true);
        batch.setVersionFreezeReason("STARTED");
        record(batch, batch.getRecipe(), batch.getRecipe(), "FREEZE",
                operatorName == null ? "班组" : operatorName,
                "批次已开始投料，配方版本与投料明细自动冻结", batch.getPlannedCost(), null);
    }

    /**
     * 操作员明确选择 KEEP 或 MIGRATE。悲观行锁 + Batch.lockVersion 保证并发时只有一个决策生效。
     */
    @Transactional
    public Batch decide(Long batchId, BatchRecipeMigrationCommand command) {
        if (command == null) {
            throw new BizException("请选择版本处理方式");
        }
        if (!Boolean.TRUE.equals(command.getImpactConfirmed())) {
            throw new BizException("请先确认已看清版本、投料明细、计划用量和成本影响");
        }
        String action = command.getAction() == null ? "" : command.getAction().trim().toUpperCase();
        if (!"KEEP".equals(action) && !"MIGRATE".equals(action)) {
            throw new BizException("版本处理方式只能是 KEEP（保留原版本）或 MIGRATE（迁移新版本）");
        }

        Batch batch;
        try {
            batchRepository.lockDetailForUpdate(batchId)
                    .orElseThrow(() -> new BizException("生产批次不存在：id=" + batchId));
            batch = batchRepository.findDetailById(batchId)
                    .orElseThrow(() -> new BizException("生产批次不存在：id=" + batchId));
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new BizException("该批次刚被其它操作更新，请刷新后查看最终版本与投料明细");
        }

        if (!command.getExpectedLockVersion().equals(batch.getLockVersion())) {
            throw new BizException("该批次版本选择已被其他操作员先提交，请刷新后查看最终版本与投料明细");
        }

        Recipe target = recipeRepository.findDetailById(command.getTargetRecipeId())
                .orElseThrow(() -> new BizException("目标配方版本不存在：id=" + command.getTargetRecipeId()));
        Recipe current = recipeRepository
                .findByFamilyKeyAndCurrentFlag(batch.getRecipe().getFamilyKey(), 1)
                .orElseThrow(() -> new BizException("未找到该配方当前发布版本，请刷新后重试"));

        String blockReason = canMigrate(batch);
        if (blockReason != null) {
            throw new BizException(blockReason);
        }
        if (!target.getFamilyKey().equals(batch.getRecipe().getFamilyKey())) {
            throw new BizException("不能把批次迁移到其它配方，只能选择同一配方的修订版本");
        }
        if (!"RELEASED".equals(target.getStatus())) {
            throw new BizException("只能迁移到已发布版本，草稿修订不能用于排产");
        }
        if ("MIGRATE".equals(action) && !target.getId().equals(current.getId())) {
            throw new BizException("目标版本不是最新发布版本，请刷新后重新选择");
        }
        if ("KEEP".equals(action) && !target.getId().equals(batch.getRecipe().getId())) {
            throw new BizException("选择保留原版本时，目标版本必须是批次当前冻结版本");
        }

        Recipe oldRecipe = batch.getRecipe();
        BigDecimal oldCost = batch.getPlannedCost();
        LocalDateTime decidedAt = LocalDateTime.now();
        if ("MIGRATE".equals(action)) {
            if (target.getId().equals(oldRecipe.getId())) {
                throw new BizException("批次已经使用该版本，无需迁移");
            }
            int itemCount = regenerateFeedings(batch, target);
            batch.setRecipe(target);
            BigDecimal plannedCost = calculatePlannedCost(target, batch.getPlannedTons());
            batch.setPlannedCost(plannedCost);
            batch.setRecipeVersionLocked(false);
            batch.setVersionFreezeReason(null);
            record(batch, oldRecipe, target, "MIGRATE", command.getOperatorName(),
                    command.getReason(), oldCost, itemCount);
        } else {
            batch.setRecipeVersionLocked(true);
            batch.setVersionFreezeReason("KEEP");
            record(batch, oldRecipe, target, "KEEP", command.getOperatorName(),
                    command.getReason(), oldCost, null);
        }
        batch.setRecipeDecisionAt(decidedAt);
        try {
            Batch saved = batchRepository.save(batch);
            enrich(saved);
            return saved;
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new BizException("已有另一个迁移/投料请求先生效，请刷新后查看最终版本与投料明细");
        }
    }

    private String canMigrate(Batch batch) {
        if (!"PLANNED".equals(batch.getStatus()) || "KEEP".equals(batch.getVersionFreezeReason())
                || "STARTED".equals(batch.getVersionFreezeReason())) {
            return "该批次配方版本已确认冻结或已开工，不能迁移；已投料/制粒/质检批次必须保持原版本与原明细";
        }
        boolean touched = batch.getFeedings().stream()
                .anyMatch(f -> f.getActualKg() != null || !"PENDING".equals(f.getStatus()));
        if (touched) {
            return "批次已有投料口开始称量或确认投料，必须保持原版本与原投料明细";
        }
        return null;
    }

    private String migrationBlockReason(Batch batch, Recipe current, boolean newerExists) {
        String reason = canMigrate(batch);
        if (reason != null) {
            return reason;
        }
        if (!newerExists) {
            return current == null ? "未找到当前发布版本" : "批次已使用当前发布版本";
        }
        return null;
    }

    /**
     * 删除旧 PENDING 明细并按新版本重建；调用前已确保没有任何开工数据，因此不会覆盖实投/异常记录。
     */
    private int regenerateFeedings(Batch batch, Recipe recipe) {
        for (FeedingItem old : List.copyOf(batch.getFeedings())) {
            batch.getFeedings().remove(old);
            feedingItemRepository.delete(old);
        }
        feedingItemRepository.flush();

        BigDecimal plannedTons = nz(batch.getPlannedTons());
        int seq = 1;
        for (RecipeItem recipeItem : recipe.getItems()) {
            FeedingItem item = new FeedingItem();
            item.setBatch(batch);
            item.setFeedSeq(seq++);
            item.setPortNo("P" + (seq - 1));
            item.setMaterial(recipeItem.getMaterial());
            BigDecimal plannedKg = plannedTons.multiply(nz(recipeItem.getRatioPct()))
                    .multiply(BigDecimal.valueOf(1000))
                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            item.setPlannedKg(plannedKg);
            item.setPlannedCost(plannedKg.multiply(nz(recipeItem.getMaterial().getUnitPrice()))
                    .divide(BigDecimal.valueOf(1000), 2, RoundingMode.HALF_UP));
            item.setStatus("PENDING");
            batch.getFeedings().add(item);
        }
        return batch.getFeedings().size();
    }

    private BigDecimal calculatePlannedCost(Recipe recipe, BigDecimal plannedTons) {
        return nz(recipe.getCostPerTon()).multiply(nz(plannedTons)).setScale(2, RoundingMode.HALF_UP);
    }

    private void record(Batch batch, Recipe from, Recipe to, String action,
                        String operatorName, String reason, BigDecimal oldPlannedCost,
                        Integer regeneratedCount) {
        BigDecimal plannedCost = calculatePlannedCost(to, batch.getPlannedTons());
        BatchRecipeMigration record = new BatchRecipeMigration();
        record.setBatch(batch);
        record.setFromRecipe(from);
        record.setToRecipe(to);
        record.setAction(action);
        record.setOperatorName(operatorName == null || operatorName.isBlank() ? "班组" : operatorName.trim());
        record.setReason(reason);
        record.setOldCostPerTon(from.getCostPerTon());
        record.setNewCostPerTon(to.getCostPerTon());
        record.setOldPlannedCost(oldPlannedCost);
        record.setNewPlannedCost(plannedCost);
        record.setRegeneratedItemCount(regeneratedCount);
        record.setCreatedAt(LocalDateTime.now());
        migrationRepository.save(record);
    }

    private String statusName(String status) {
        Map<String, String> names = new HashMap<>();
        names.put("FEEDING", "投料中");
        names.put("PELLETING", "制粒中");
        names.put("QC", "质检中");
        names.put("RECHECK", "复检中");
        names.put("DONE", "已完成");
        return names.getOrDefault(status, status);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
