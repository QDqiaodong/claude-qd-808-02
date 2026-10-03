package com.feed.mill.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feed.mill.common.BizException;
import com.feed.mill.domain.Material;
import com.feed.mill.domain.Recipe;
import com.feed.mill.domain.RecipeItem;
import com.feed.mill.domain.RecipeRevisionLog;
import com.feed.mill.dto.RatioAdjustCommand;
import com.feed.mill.dto.RevisionCommand;
import com.feed.mill.dto.SpeciesRecipeStatVO;
import com.feed.mill.repository.BatchRepository;
import com.feed.mill.repository.RecipeRepository;
import com.feed.mill.repository.RecipeRevisionLogRepository;

/**
 * 配方：占比微调、锁定平摊、占比合计 100% 与单原料上限校验、吨成本重算。
 * 明细集合是 {@code @SortNatural} 的 SortedSet，输出天然按原料顺序排好。
 * <p>
 * 修订版本：已发布配方是只读快照，改动必须「创建修订」生成 DRAFT 草稿，微调后再发布。
 * 发布修订时原子收口（条件更新 + 唯一索引），多人同时发布只有第一个生效；
 * 同时把仍引用旧版本的计划中批次标记为「待选择」（默认冻结在原版本快照），并写入含差异快照的修订记录。
 */
@Service
public class RecipeService {

    /** 合计目标 100% */
    private static final BigDecimal TOTAL_TARGET = new BigDecimal("100");
    /** 允许误差 */
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    /** 状态：草稿 */
    public static final String DRAFT = "DRAFT";
    /** 状态：当前生效 */
    public static final String RELEASED = "RELEASED";
    /** 状态：已被取代（历史快照） */
    public static final String SUPERSEDED = "SUPERSEDED";

    private static final Map<String, String> SPECIES_NAME = Map.of(
            "SWINE", "猪",
            "POULTRY", "禽",
            "AQUA", "水产",
            "RUMINANT", "反刍");

    private final RecipeRepository recipeRepository;
    private final BatchRepository batchRepository;
    private final RecipeRevisionLogRepository revisionLogRepository;
    private final PlatformTransactionManager transactionManager;
    private final ObjectMapper objectMapper;

    public RecipeService(RecipeRepository recipeRepository,
                         BatchRepository batchRepository,
                         RecipeRevisionLogRepository revisionLogRepository,
                         PlatformTransactionManager transactionManager,
                         ObjectMapper objectMapper) {
        this.recipeRepository = recipeRepository;
        this.batchRepository = batchRepository;
        this.revisionLogRepository = revisionLogRepository;
        this.transactionManager = transactionManager;
        this.objectMapper = objectMapper;
    }

    /** 列表口径：当前生效版本 + 修订草稿（被取代的历史版本走版本链接口） */
    @Transactional(readOnly = true)
    public List<Recipe> list() {
        return recipeRepository.findCurrentAndDraft();
    }

    @Transactional(readOnly = true)
    public Recipe detail(Long id) {
        return recipeRepository.findById(id)
                .orElseThrow(() -> new BizException("配方不存在：id=" + id));
    }

    /** 版本链：同一配方编号的全部修订版本（含历史快照），按版本号升序 */
    @Transactional(readOnly = true)
    public List<Recipe> revisions(Long id) {
        Recipe recipe = detail(id);
        return recipeRepository.findByRecipeCodeOrderByRevisionNo(recipe.getRecipeCode());
    }

    /** 修订记录（含修订前后差异快照），新记录在前 */
    @Transactional(readOnly = true)
    public List<RecipeRevisionLog> revisionLogs(Long id) {
        Recipe recipe = detail(id);
        return revisionLogRepository.findByRecipeCodeOrderByIdDesc(recipe.getRecipeCode());
    }

    /**
     * 创建修订草稿：基于当前生效版本复制一份（revision_no+1），明细逐条拷贝并按最新单价重算成本。
     * 同一配方族同一时刻只允许一个草稿；并发创建由 uk_recipe_code_rev 唯一索引兜底。
     */
    @Transactional
    public Recipe revise(Long recipeId, RevisionCommand command) {
        Recipe base = detail(recipeId);
        if (!RELEASED.equals(base.getStatus())) {
            throw new BizException("只有当前生效版本才能发起修订，配方 " + base.getRecipeCode()
                    + " V" + base.getRevisionNo() + " 的状态是「" + statusName(base.getStatus()) + "」");
        }
        recipeRepository.findFirstByRecipeCodeAndStatus(base.getRecipeCode(), DRAFT)
                .ifPresent(draft -> {
                    throw new BizException("配方 " + base.getRecipeCode() + " 已存在修订草稿 V"
                            + draft.getRevisionNo() + "（修订人：" + nullSafe(draft.getOperatorName())
                            + "），请先发布或废弃该草稿");
                });

        Recipe draft = new Recipe();
        draft.setRecipeCode(base.getRecipeCode());
        draft.setRevisionNo(base.getRevisionNo() + 1);
        draft.setBasedOnId(base.getId());
        draft.setRevisionNote(command.getNote() == null ? null : command.getNote().trim());
        draft.setOperatorName(command.getOperatorName().trim());
        draft.setActiveFlag(null);
        draft.setStatus(DRAFT);
        draft.setName(base.getName());
        draft.setSpecies(base.getSpecies());
        draft.setStage(base.getStage());
        draft.setCrudeProtein(base.getCrudeProtein());
        draft.setMetabolicEnergy(base.getMetabolicEnergy());
        draft.setLysine(base.getLysine());
        for (RecipeItem src : base.getItems()) {
            RecipeItem item = new RecipeItem();
            item.setRecipe(draft);
            item.setMaterial(src.getMaterial());
            item.setRatioPct(src.getRatioPct());
            item.setLocked(src.getLocked());
            item.setSortHint(src.getSortHint());
            draft.getItems().add(item);
        }
        recomputeCost(draft);
        try {
            // 并发兜底：uk_recipe_code_rev 保证同版本号只落一行，撞索引即他人已抢先建稿
            return recipeRepository.saveAndFlush(draft);
        } catch (DataIntegrityViolationException e) {
            throw new BizException("配方 " + base.getRecipeCode() + " 的 V" + draft.getRevisionNo()
                    + " 修订草稿已被他人创建，请刷新后查看");
        }
    }

    /**
     * 废弃修订草稿：草稿未被任何批次引用（批次只引用生效版本），可直接删除。
     */
    @Transactional
    public void discard(Long recipeId) {
        Recipe recipe = detail(recipeId);
        if (!DRAFT.equals(recipe.getStatus())) {
            throw new BizException("只有修订草稿可以废弃，配方 " + recipe.getRecipeCode()
                    + " V" + recipe.getRevisionNo() + " 的状态是「" + statusName(recipe.getStatus()) + "」");
        }
        recipeRepository.delete(recipe);
    }

    /**
     * 占比微调：delta 为百分点（前端 ±0.5）。
     * 规则一：单一原料占比不得超过该原料上限（鱼粉 ≤ 8%）
     * 规则二：合计不得超过 100%
     * 规则三：只有 DRAFT 草稿可改，已发布/被取代版本是只读快照
     */
    @Transactional
    public Recipe adjust(Long recipeId, RatioAdjustCommand command) {
        Recipe recipe = detail(recipeId);
        ensureEditable(recipe);
        RecipeItem item = findItem(recipe, command.getItemId());

        Boolean spread = command.getSpread();
        if (Boolean.TRUE.equals(spread)) {
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
            throw new BizException("配方 " + recipe.getRecipeCode() + " 占比合计已达 " + fmt(total)
                    + "%，超出 100% 上限 " + fmt(total.subtract(TOTAL_TARGET)) + " 个百分点，请先调低其它原料");
        }
        recomputeCost(recipe);
        return recipeRepository.save(recipe);
    }

    /**
     * 锁定某原料占比，其余原料按现有比例自动平摊到 100%。
     */
    @Transactional
    public Recipe spread(Long recipeId, RatioAdjustCommand command) {
        Recipe recipe = detail(recipeId);
        ensureEditable(recipe);
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
     * 发布校验：占比合计必须 100%，否则给出差值。
     * <p>
     * 首次发布（无基于版本）：草稿直接生效。
     * 修订发布：先把原生效版本原子收口为 SUPERSEDED（多人同时发布只有第一个生效），
     * 再把草稿置为 RELEASED；随后把仍引用旧版本的计划中批次标记为「待选择」，
     * 并写入修订记录（含修订前后明细差异快照）。
     */
    @Transactional
    public Recipe submit(Long recipeId) {
        Recipe recipe = detail(recipeId);
        if (RELEASED.equals(recipe.getStatus())) {
            throw new BizException("配方 " + recipe.getRecipeCode() + " V" + recipe.getRevisionNo()
                    + " 已是当前生效版本，无需重复发布");
        }
        if (SUPERSEDED.equals(recipe.getStatus())) {
            throw new BizException("配方 " + recipe.getRecipeCode() + " V" + recipe.getRevisionNo()
                    + " 已被新版本取代，是只读历史快照，不能发布");
        }
        BigDecimal total = totalRatio(recipe);
        BigDecimal diff = TOTAL_TARGET.subtract(total);
        if (diff.abs().compareTo(TOLERANCE) > 0) {
            throw new BizException("配方 " + recipe.getRecipeCode() + " 明细占比合计 " + fmt(total)
                    + "%，与 100% 相差 " + fmt(diff.abs()) + " 个百分点（"
                    + (diff.compareTo(BigDecimal.ZERO) > 0 ? "还差" : "超出") + "），不允许发布");
        }
        recomputeCost(recipe);
        // 先落成本，再进入收口段（bulk update 不经过持久化上下文）
        recipeRepository.saveAndFlush(recipe);

        Recipe base = recipe.getBasedOnId() == null ? null : detail(recipe.getBasedOnId());
        if (base != null) {
            // 原子收口①：只有仍是 RELEASED 的原版本才能被取代，影响行数=0 即他人已抢先发布
            int superseded = recipeRepository.markSupersededIfReleased(base.getId());
            if (superseded == 0) {
                Recipe committedBase = committedRecipe(base.getId());
                Recipe current = committedCurrent(recipe.getRecipeCode());
                throw new BizException("原版本 V" + base.getRevisionNo() + " 已变为「"
                        + statusName(committedBase.getStatus()) + "」"
                        + (current == null ? "" : "，当前生效版本是 V" + current.getRevisionNo())
                        + "，本草稿基于旧版本，请废弃后基于最新版本重新修订");
            }
        }
        // 原子收口②：只有仍是 DRAFT 的草稿才能被发布（并发重复提交收口）
        int released = recipeRepository.markReleasedIfDraft(recipe.getId());
        if (released == 0) {
            throw new BizException("配方 " + recipe.getRecipeCode() + " V" + recipe.getRevisionNo()
                    + " 已被发布，请勿重复提交");
        }

        // 同步内存态（markXxx 是 bulk update，不经过持久化上下文）
        recipe.setStatus(RELEASED);
        recipe.setActiveFlag(1);
        if (base != null) {
            base.setStatus(SUPERSEDED);
            base.setActiveFlag(null);
        }

        // 默认冻结 + 主动提示：仍引用旧版本快照的计划中批次标记为「待选择」，
        // 批次本身不动（继续用原版本），等操作员看清差异后决定迁移或确认冻结；
        // 已开始投料/制粒/质检的批次不在此列，永远保持原版本与原明细。
        int affected = batchRepository.markPendingForSuperseded(recipe.getRecipeCode());

        // 修订记录：差异快照 + 影响批次数，全程可追溯
        RecipeRevisionLog log = new RecipeRevisionLog();
        log.setRecipeCode(recipe.getRecipeCode());
        log.setFromRecipeId(base == null ? null : base.getId());
        log.setToRecipeId(recipe.getId());
        log.setFromRevisionNo(base == null ? null : base.getRevisionNo());
        log.setToRevisionNo(recipe.getRevisionNo());
        log.setOperatorName(recipe.getOperatorName() == null ? "系统" : recipe.getOperatorName());
        log.setNote(recipe.getRevisionNote());
        log.setAffectedBatches(affected);
        log.setCostBefore(base == null ? null : base.getCostPerTon());
        log.setCostAfter(recipe.getCostPerTon());
        log.setDiffJson(buildRevisionDiff(base, recipe));
        log.setCreatedAt(LocalDateTime.now());
        revisionLogRepository.save(log);
        return recipe;
    }

    /** 修订前后明细差异快照（JSON）：按原料对照新旧占比与成本小计，标记新增/移除/变化 */
    private String buildRevisionDiff(Recipe base, Recipe draft) {
        Map<Long, RecipeItem> before = new LinkedHashMap<>();
        if (base != null) {
            for (RecipeItem item : base.getItems()) {
                before.put(item.getMaterial().getId(), item);
            }
        }
        Map<Long, RecipeItem> after = new LinkedHashMap<>();
        for (RecipeItem item : draft.getItems()) {
            after.put(item.getMaterial().getId(), item);
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map.Entry<Long, RecipeItem> entry : after.entrySet()) {
            RecipeItem newItem = entry.getValue();
            RecipeItem oldItem = before.remove(entry.getKey());
            items.add(diffRow(newItem.getMaterial(),
                    oldItem == null ? null : nz(oldItem.getRatioPct()), nz(newItem.getRatioPct()),
                    oldItem == null ? null : nz(oldItem.getCostSubtotal()), nz(newItem.getCostSubtotal()),
                    oldItem == null ? "ADDED"
                            : nz(oldItem.getRatioPct()).compareTo(nz(newItem.getRatioPct())) == 0
                            ? "SAME" : "CHANGED"));
        }
        for (RecipeItem removed : before.values()) {
            items.add(diffRow(removed.getMaterial(),
                    nz(removed.getRatioPct()), null, nz(removed.getCostSubtotal()), null, "REMOVED"));
        }

        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("fromRevisionNo", base == null ? null : base.getRevisionNo());
        diff.put("toRevisionNo", draft.getRevisionNo());
        diff.put("costPerTonBefore", base == null ? null : base.getCostPerTon());
        diff.put("costPerTonAfter", draft.getCostPerTon());
        diff.put("items", items);
        try {
            return objectMapper.writeValueAsString(diff);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static Map<String, Object> diffRow(Material material, BigDecimal ratioBefore, BigDecimal ratioAfter,
                                               BigDecimal costBefore, BigDecimal costAfter, String change) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("materialCode", material.getMaterialCode());
        row.put("materialName", material.getName());
        row.put("change", change);
        row.put("ratioBefore", ratioBefore);
        row.put("ratioAfter", ratioAfter);
        row.put("costBefore", costBefore);
        row.put("costAfter", costAfter);
        return row;
    }

    /** 只有 DRAFT 草稿允许改明细；已发布/被取代版本是只读快照 */
    private void ensureEditable(Recipe recipe) {
        if (!DRAFT.equals(recipe.getStatus())) {
            throw new BizException("配方 " + recipe.getRecipeCode() + " V" + recipe.getRevisionNo()
                    + " 已" + (RELEASED.equals(recipe.getStatus()) ? "发布" : "被取代")
                    + "，是只读快照，请先「创建修订」生成草稿再调整");
        }
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

    /** 按畜种统计配方数与成本（手写 JPQL，只统计当前生效版本） */
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

    /** 在新事务（REQUIRES_NEW）里读取已提交的配方版本，绕过当前事务的可重复读快照 */
    private Recipe committedRecipe(Long id) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(true);
        return template.execute(status -> recipeRepository.findById(id)
                .orElseThrow(() -> new BizException("配方不存在：id=" + id)));
    }

    /** 在新事务里读取该配方族已提交的当前生效版本（并发收口失败时给后到请求看真实结果） */
    private Recipe committedCurrent(String recipeCode) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(true);
        return template.execute(status -> recipeRepository
                .findFirstByRecipeCodeAndActiveFlag(recipeCode, 1).orElse(null));
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

    private static String statusName(String status) {
        if (status == null) {
            return "-";
        }
        return switch (status) {
            case DRAFT -> "草稿";
            case RELEASED -> "生效中";
            case SUPERSEDED -> "已被取代";
            default -> status;
        };
    }

    private static String nullSafe(String value) {
        return value == null ? "-" : value;
    }

    private static String fmt(BigDecimal value) {
        return value == null ? "0" : value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
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
