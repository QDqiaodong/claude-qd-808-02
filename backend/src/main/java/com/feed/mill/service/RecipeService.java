package com.feed.mill.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.feed.mill.common.BizException;
import com.feed.mill.domain.Material;
import com.feed.mill.domain.Recipe;
import com.feed.mill.domain.RecipeItem;
import com.feed.mill.domain.RecipeRevisionDiff;
import com.feed.mill.dto.RatioAdjustCommand;
import com.feed.mill.dto.RecipeRevisionCommand;
import com.feed.mill.dto.SpeciesRecipeStatVO;
import com.feed.mill.repository.RecipeRepository;
import com.feed.mill.repository.RecipeRevisionDiffRepository;

/**
 * 配方修订：已发布版本只读；复制成 DRAFT 后调整，发布时生成版本差异并切换当前版本。
 */
@Service
public class RecipeService {

    /** 合计目标 100% */
    private static final BigDecimal TOTAL_TARGET = new BigDecimal("100");
    /** 允许误差 */
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private static final Map<String, String> SPECIES_NAME = Map.of(
            "SWINE", "猪",
            "POULTRY", "禽",
            "AQUA", "水产",
            "RUMINANT", "反刍");

    private final RecipeRepository recipeRepository;
    private final RecipeRevisionDiffRepository diffRepository;
    private final BatchMigrationService batchMigrationService;

    public RecipeService(RecipeRepository recipeRepository,
                         RecipeRevisionDiffRepository diffRepository,
                         BatchMigrationService batchMigrationService) {
        this.recipeRepository = recipeRepository;
        this.diffRepository = diffRepository;
        this.batchMigrationService = batchMigrationService;
    }

    @Transactional(readOnly = true)
    public List<Recipe> list() {
        return recipeRepository.findAll().stream()
                .sorted(Comparator.comparing(Recipe::getFamilyKey)
                        .thenComparing(Recipe::getVersionNo, Comparator.reverseOrder())
                        .thenComparing(Recipe::getId, Comparator.reverseOrder()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Recipe detail(Long id) {
        return recipeRepository.findDetailById(id)
                .orElseThrow(() -> new BizException("配方不存在：id=" + id));
    }

    @Transactional(readOnly = true)
    public List<Recipe> versions(Long recipeId) {
        Recipe recipe = detail(recipeId);
        return recipeRepository.findByFamilyKeyOrderByVersionNoDesc(recipe.getFamilyKey());
    }

    @Transactional(readOnly = true)
    public List<RecipeRevisionDiff> revisionDiffs(Long recipeId) {
        Recipe recipe = detail(recipeId);
        if (recipe.getSourceRecipe() == null) {
            throw new BizException("配方 " + recipe.getRecipeCode() + " V" + recipe.getVersionNo()
                    + " 是初始版本，没有修订前后差异");
        }
        return diffRepository.findByRecipeIdOrderByIdAsc(recipeId);
    }

    /**
     * 基于当前已发布版本创建下一版草稿。已发布版本永不原地修改。
     * 行级锁 + active_draft_flag 唯一索引共同保证多人同时发起修订时只有一个草稿。
     */
    @Transactional
    public Recipe createRevision(Long sourceId, RecipeRevisionCommand command) {
        Recipe source = recipeRepository.findDetailById(sourceId)
                .orElseThrow(() -> new BizException("配方不存在：id=" + sourceId));
        if (!"RELEASED".equals(source.getStatus()) || !Integer.valueOf(1).equals(source.getCurrentFlag())) {
            throw new BizException("只能基于当前已发布版本发起修订，请刷新后选择最新版本");
        }

        recipeRepository.lockFamily(source.getFamilyKey());
        List<Recipe> family = recipeRepository.findByFamilyKeyOrderByVersionNoDesc(source.getFamilyKey());
        boolean draftExists = family.stream().anyMatch(r -> Integer.valueOf(1).equals(r.getActiveDraftFlag()));
        if (draftExists) {
            throw new BizException("配方 " + source.getRecipeCode() + " 已有进行中的修订草稿，请先发布或放弃该草稿");
        }

        int nextVersion = family.stream().map(Recipe::getVersionNo).max(Integer::compareTo).orElse(1) + 1;
        Recipe draft = new Recipe();
        draft.setRecipeCode(source.getRecipeCode());
        draft.setFamilyKey(source.getFamilyKey());
        draft.setVersionNo(nextVersion);
        draft.setSourceRecipe(source);
        draft.setName(source.getName());
        draft.setSpecies(source.getSpecies());
        draft.setStage(source.getStage());
        draft.setCrudeProtein(source.getCrudeProtein());
        draft.setMetabolicEnergy(source.getMetabolicEnergy());
        draft.setLysine(source.getLysine());
        draft.setStatus("DRAFT");
        draft.setActiveDraftFlag(1);
        draft.setCostPerTon(source.getCostPerTon());
        draft.setPublishedBy(null);
        draft.setChangeSummary(command == null ? null : command.getChangeSummary());
        for (RecipeItem sourceItem : source.getItems()) {
            RecipeItem item = new RecipeItem();
            item.setRecipe(draft);
            item.setMaterial(sourceItem.getMaterial());
            item.setRatioPct(sourceItem.getRatioPct());
            item.setCostSubtotal(sourceItem.getCostSubtotal());
            item.setLocked(sourceItem.getLocked());
            item.setSortHint(sourceItem.getSortHint());
            draft.getItems().add(item);
        }
        return recipeRepository.save(draft);
    }

    /**
     * 占比微调仅允许作用于 DRAFT 修订稿。
     */
    @Transactional
    public Recipe adjust(Long recipeId, RatioAdjustCommand command) {
        Recipe recipe = requireDraft(recipeId);
        RecipeItem item = findItem(recipe, command.getItemId());

        if (Boolean.TRUE.equals(command.getSpread())) {
            return spread(recipe, item);
        }

        BigDecimal delta = command.getDelta() == null ? BigDecimal.ZERO : command.getDelta();
        BigDecimal next = nz(item.getRatioPct()).add(delta).setScale(2, RoundingMode.HALF_UP);
        if (next.compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException(item.getMaterial().getName() + " 占比不能为负数");
        }
        item.setRatioPct(next);
        if (command.getLocked() != null) {
            item.setLocked(command.getLocked());
        }

        checkMaxRatio(item);

        BigDecimal total = totalRatio(recipe);
        if (total.compareTo(TOTAL_TARGET) > 0) {
            throw new BizException("配方 " + recipe.getRecipeCode() + " V" + recipe.getVersionNo()
                    + " 占比合计已达 " + fmt(total) + "%，超出 100% 上限 " + fmt(total.subtract(TOTAL_TARGET))
                    + " 个百分点，请先调低其它原料");
        }
        recomputeCost(recipe);
        return recipeRepository.save(recipe);
    }

    @Transactional
    public Recipe spread(Long recipeId, RatioAdjustCommand command) {
        Recipe recipe = requireDraft(recipeId);
        RecipeItem locked = findItem(recipe, command.getItemId());
        return spread(recipe, locked);
    }

    private Recipe spread(Recipe recipe, RecipeItem locked) {
        locked.setLocked(true);
        List<RecipeItem> others = new ArrayList<>();
        for (RecipeItem item : recipe.getItems()) {
            if (!item.getId().equals(locked.getId())) {
                item.setLocked(false);
                others.add(item);
            }
        }
        if (others.isEmpty()) {
            throw new BizException("配方仅有一味原料，无需平摊");
        }

        BigDecimal lockedRatio = nz(locked.getRatioPct()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal remain = TOTAL_TARGET.subtract(lockedRatio);
        if (remain.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(locked.getMaterial().getName() + " 锁定占比 " + fmt(lockedRatio)
                    + "%，已无余量分给其它原料");
        }

        BigDecimal othersSum = BigDecimal.ZERO;
        for (RecipeItem item : others) {
            othersSum = othersSum.add(nz(item.getRatioPct()));
        }

        if (othersSum.compareTo(BigDecimal.ZERO) <= 0) {
            BigDecimal each = remain.divide(BigDecimal.valueOf(others.size()), 2, RoundingMode.HALF_UP);
            for (RecipeItem item : others) {
                item.setRatioPct(each);
            }
        } else {
            for (RecipeItem item : others) {
                BigDecimal scaled = nz(item.getRatioPct()).multiply(remain)
                        .divide(othersSum, 2, RoundingMode.HALF_UP);
                item.setRatioPct(scaled);
            }
        }
        fixResidual(recipe, others);

        for (RecipeItem item : recipe.getItems()) {
            checkMaxRatio(item);
        }
        recomputeCost(recipe);
        return recipeRepository.save(recipe);
    }

    /** 把四舍五入产生的尾差补到占比最大的未锁定明细上，保证恰好 100% */
    private void fixResidual(Recipe recipe, List<RecipeItem> others) {
        BigDecimal total = totalRatio(recipe);
        BigDecimal diff = TOTAL_TARGET.subtract(total);
        if (diff.abs().compareTo(TOLERANCE) <= 0) {
            return;
        }
        RecipeItem biggest = others.stream()
                .max(Comparator.comparing(i -> nz(i.getRatioPct())))
                .orElse(null);
        if (biggest == null) {
            return;
        }
        biggest.setRatioPct(nz(biggest.getRatioPct()).add(diff).setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * 发布修订：校验配方、原子切换 current_flag，并把旧版本标记为 SUPERSEDED。
     */
    @Transactional
    public Recipe submit(Long recipeId) {
        return submit(recipeId, null);
    }

    @Transactional
    public Recipe submit(Long recipeId, RecipeRevisionCommand command) {
        Recipe draft = recipeRepository.findDetailById(recipeId)
                .orElseThrow(() -> new BizException("配方不存在：id=" + recipeId));
        if (!"DRAFT".equals(draft.getStatus()) || !Integer.valueOf(1).equals(draft.getActiveDraftFlag())) {
            throw new BizException("当前版本已经发布，不能原地修改；请发起新的修订版本");
        }
        Recipe source = draft.getSourceRecipe();
        if (source == null) {
            throw new BizException("修订草稿缺少来源版本，无法发布");
        }

        // 锁住配方族：并发发布/创建修订在数据库层串行，乐观锁负责拒绝过期提交。
        recipeRepository.lockFamily(draft.getFamilyKey());
        Long currentId = recipeRepository.findByFamilyKeyAndCurrentFlag(draft.getFamilyKey(), 1)
                .map(Recipe::getId)
                .orElseThrow(() -> new BizException("未找到当前已发布版本，请刷新后重试"));
        Recipe current = recipeRepository.findDetailById(currentId)
                .orElseThrow(() -> new BizException("未找到当前已发布版本，请刷新后重试"));
        if (!current.getId().equals(source.getId())) {
            throw new BizException("来源版本已被其它修订替代，请基于最新版本重新发起修订");
        }

        validateRecipe(draft);
        recomputeCost(draft);
        if (command != null) {
            if (command.getOperatorName() != null && !command.getOperatorName().isBlank()) {
                draft.setPublishedBy(command.getOperatorName().trim());
            }
            if (command.getChangeSummary() != null && !command.getChangeSummary().isBlank()) {
                draft.setChangeSummary(command.getChangeSummary().trim());
            }
        }
        if (draft.getPublishedBy() == null || draft.getPublishedBy().isBlank()) {
            throw new BizException("请填写修订发布人");
        }

        current.setStatus("SUPERSEDED");
        current.setCurrentFlag(null);
        recipeRepository.save(current);
        recipeRepository.flush();

        draft.setStatus("RELEASED");
        draft.setCurrentFlag(1);
        draft.setActiveDraftFlag(null);
        draft.setPublishedAt(LocalDateTime.now());
        Recipe saved = recipeRepository.save(draft);
        createRevisionDiffs(saved, current);
        batchMigrationService.freezePlannedBatchesOnPublish(current, saved,
                draft.getPublishedBy() == null || draft.getPublishedBy().isBlank() ? "系统" : draft.getPublishedBy());
        return recipeRepository.findDetailById(saved.getId()).orElse(saved);
    }

    private void validateRecipe(Recipe recipe) {
        BigDecimal total = totalRatio(recipe);
        BigDecimal diff = TOTAL_TARGET.subtract(total);
        if (diff.abs().compareTo(TOLERANCE) > 0) {
            throw new BizException("配方 " + recipe.getRecipeCode() + " V" + recipe.getVersionNo()
                    + " 明细占比合计 " + fmt(total) + "%，与 100% 相差 " + fmt(diff.abs())
                    + " 个百分点（" + (diff.compareTo(BigDecimal.ZERO) > 0 ? "还差" : "超出") + "），不允许发布");
        }
        for (RecipeItem item : recipe.getItems()) {
            checkMaxRatio(item);
        }
    }

    private void createRevisionDiffs(Recipe draft, Recipe source) {
        List<RecipeRevisionDiff> diffs = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        addHeaderDiff(diffs, draft, source, "CRUDE_PROTEIN", "粗蛋白", source.getCrudeProtein(), draft.getCrudeProtein(), "%", now);
        addHeaderDiff(diffs, draft, source, "METABOLIC_ENERGY", "代谢能", source.getMetabolicEnergy(), draft.getMetabolicEnergy(), "MJ/kg", now);
        addHeaderDiff(diffs, draft, source, "LYSINE", "赖氨酸", source.getLysine(), draft.getLysine(), "%", now);

        Map<Long, RecipeItem> sourceItems = new HashMap<>();
        for (RecipeItem item : source.getItems()) {
            sourceItems.put(item.getMaterial().getId(), item);
        }
        Map<Long, RecipeItem> newItems = new HashMap<>();
        for (RecipeItem item : draft.getItems()) {
            newItems.put(item.getMaterial().getId(), item);
        }

        for (RecipeItem oldItem : source.getItems()) {
            RecipeItem newItem = newItems.get(oldItem.getMaterial().getId());
            if (newItem == null) {
                diffs.add(itemDiff(draft, source, oldItem, null, "REMOVED", now));
            } else if (changed(oldItem, newItem)) {
                diffs.add(itemDiff(draft, source, oldItem, newItem, "MODIFIED", now));
            }
        }
        for (RecipeItem newItem : draft.getItems()) {
            if (!sourceItems.containsKey(newItem.getMaterial().getId())) {
                diffs.add(itemDiff(draft, source, null, newItem, "ADDED", now));
            }
        }
        if (diffs.isEmpty()) {
            throw new BizException("修订内容与原版本完全相同，未检测到占比、成本或营养指标差异，无需发布");
        }
        diffRepository.saveAll(diffs);
    }

    private void addHeaderDiff(List<RecipeRevisionDiff> diffs, Recipe draft, Recipe source,
                               String code, String name, BigDecimal oldValue, BigDecimal newValue,
                               String unit, LocalDateTime now) {
        if (!nz(oldValue).equals(nz(newValue))) {
            RecipeRevisionDiff diff = baseDiff(draft, source, now);
            diff.setItemCode(code);
            diff.setItemName(name);
            diff.setChangeType("MODIFIED");
            diff.setOldValue(plain(oldValue));
            diff.setNewValue(plain(newValue));
            diff.setUnit(unit);
            diffs.add(diff);
        }
    }

    private RecipeRevisionDiff itemDiff(Recipe draft, Recipe source, RecipeItem oldItem,
                                        RecipeItem newItem, String type, LocalDateTime now) {
        RecipeRevisionDiff diff = baseDiff(draft, source, now);
        RecipeItem any = oldItem == null ? newItem : oldItem;
        diff.setMaterial(any.getMaterial());
        diff.setItemCode(any.getMaterial().getMaterialCode());
        diff.setItemName(any.getMaterial().getName());
        diff.setChangeType(type);
        if (oldItem != null) {
            diff.setOldRatioPct(oldItem.getRatioPct());
            diff.setOldCostSubtotal(oldItem.getCostSubtotal());
        }
        if (newItem != null) {
            diff.setNewRatioPct(newItem.getRatioPct());
            diff.setNewCostSubtotal(newItem.getCostSubtotal());
        }
        return diff;
    }

    private RecipeRevisionDiff baseDiff(Recipe draft, Recipe source, LocalDateTime now) {
        RecipeRevisionDiff diff = new RecipeRevisionDiff();
        diff.setRecipe(draft);
        diff.setSourceRecipe(source);
        diff.setVersionNo(draft.getVersionNo());
        diff.setCreatedAt(now);
        return diff;
    }

    private boolean changed(RecipeItem oldItem, RecipeItem newItem) {
        return nz(oldItem.getRatioPct()).compareTo(nz(newItem.getRatioPct())) != 0
                || nz(oldItem.getCostSubtotal()).compareTo(nz(newItem.getCostSubtotal())) != 0;
    }

    /** 单原料占比上限校验 */
    private void checkMaxRatio(RecipeItem item) {
        Material material = item.getMaterial();
        BigDecimal max = material.getMaxRatio() == null ? TOTAL_TARGET : material.getMaxRatio();
        BigDecimal ratio = nz(item.getRatioPct());
        if (ratio.compareTo(max) > 0) {
            throw new BizException(material.getName() + " 占比 " + fmt(ratio) + "% 超过上限 "
                    + fmt(max) + "%，请把超出部分调给其它原料");
        }
    }

    /** 重算成本小计与吨成本 */
    private void recomputeCost(Recipe recipe) {
        BigDecimal total = BigDecimal.ZERO;
        for (RecipeItem item : recipe.getItems()) {
            BigDecimal price = item.getMaterial() == null ? BigDecimal.ZERO : nz(item.getMaterial().getUnitPrice());
            BigDecimal subtotal = nz(item.getRatioPct()).multiply(price)
                    .divide(TOTAL_TARGET, 2, RoundingMode.HALF_UP);
            item.setCostSubtotal(subtotal);
            total = total.add(subtotal);
        }
        recipe.setCostPerTon(total.setScale(2, RoundingMode.HALF_UP));
    }

    @Transactional(readOnly = true)
    public BigDecimal totalRatio(Recipe recipe) {
        BigDecimal total = BigDecimal.ZERO;
        for (RecipeItem item : recipe.getItems()) {
            total = total.add(nz(item.getRatioPct()));
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /** 按畜种统计配方族数与当前版本成本（手写 JPQL） */
    @Transactional(readOnly = true)
    public List<SpeciesRecipeStatVO> speciesStats() {
        List<SpeciesRecipeStatVO> result = new ArrayList<>();
        for (Object[] row : recipeRepository.speciesStats()) {
            SpeciesRecipeStatVO vo = new SpeciesRecipeStatVO();
            String species = (String) row[0];
            vo.setSpecies(species);
            vo.setSpeciesName(SPECIES_NAME.getOrDefault(species, species));
            vo.setRecipeCount(toLong(row[1]));
            vo.setAvgCost(scale2(row[2]));
            vo.setMinCost(scale2(row[3]));
            vo.setMaxCost(scale2(row[4]));
            vo.setReleasedCount(toLong(row[5]));
            result.add(vo);
        }
        return result;
    }

    private Recipe requireDraft(Long recipeId) {
        Recipe recipe = detail(recipeId);
        if (!"DRAFT".equals(recipe.getStatus()) || !Integer.valueOf(1).equals(recipe.getActiveDraftFlag())) {
            throw new BizException("已发布版本为只读快照，请先发起修订并在新草稿上调整");
        }
        return recipe;
    }

    private RecipeItem findItem(Recipe recipe, Long itemId) {
        if (itemId == null) {
            throw new BizException("未指定配方明细");
        }
        return recipe.getItems().stream()
                .filter(i -> itemId.equals(i.getId()))
                .findFirst()
                .orElseThrow(() -> new BizException("配方明细不存在：id=" + itemId));
    }

    private static String fmt(BigDecimal value) {
        return value == null ? "0" : value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static String plain(BigDecimal value) {
        return Optional.ofNullable(value).map(BigDecimal::toPlainString).orElse(null);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
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
