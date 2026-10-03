package com.feed.mill.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.feed.mill.common.BizException;
import com.feed.mill.domain.Material;
import com.feed.mill.domain.Recipe;
import com.feed.mill.domain.RecipeItem;
import com.feed.mill.dto.RatioAdjustCommand;
import com.feed.mill.dto.SpeciesRecipeStatVO;
import com.feed.mill.repository.RecipeRepository;

/**
 * 配方：占比微调、锁定平摊、占比合计 100% 与单原料上限校验、吨成本重算。
 * 明细集合是 {@code @SortNatural} 的 SortedSet，输出天然按原料顺序排好。
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

    public RecipeService(RecipeRepository recipeRepository) {
        this.recipeRepository = recipeRepository;
    }

    @Transactional(readOnly = true)
    public List<Recipe> list() {
        return recipeRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Recipe detail(Long id) {
        return recipeRepository.findById(id)
                .orElseThrow(() -> new BizException("配方不存在：id=" + id));
    }

    /**
     * 占比微调：delta 为百分点（前端 ±0.5）。
     * 规则一：单一原料占比不得超过该原料上限（鱼粉 ≤ 8%）
     * 规则二：合计不得超过 100%
     */
    @Transactional
    public Recipe adjust(Long recipeId, RatioAdjustCommand command) {
        Recipe recipe = detail(recipeId);
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
     * 发布/保存校验：占比合计必须 100%，否则给出差值。
     */
    @Transactional
    public Recipe submit(Long recipeId) {
        Recipe recipe = detail(recipeId);
        BigDecimal total = totalRatio(recipe);
        BigDecimal diff = TOTAL_TARGET.subtract(total);
        if (diff.abs().compareTo(TOLERANCE) > 0) {
            throw new BizException("配方 " + recipe.getRecipeCode() + " 明细占比合计 " + fmt(total)
                    + "%，与 100% 相差 " + fmt(diff.abs()) + " 个百分点（"
                    + (diff.compareTo(BigDecimal.ZERO) > 0 ? "还差" : "超出") + "），不允许发布");
        }
        recipe.setStatus("RELEASED");
        recomputeCost(recipe);
        return recipeRepository.save(recipe);
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

    /** 按畜种统计配方数与成本（手写 JPQL） */
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
