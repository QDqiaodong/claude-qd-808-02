package com.feed.mill.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.feed.mill.domain.Batch;
import com.feed.mill.domain.Recipe;

/**
 * 生产批次。
 * 配方迁移并发收口：{@link #migrateIfPlanned} / {@link #freezeIfPlanned} 条件更新原子占位，
 * 多人同时迁移/冻结同一批次时只有第一个请求改到行，后到请求影响行数=0 看到已有结果。
 */
public interface BatchRepository extends JpaRepository<Batch, Long> {

    @Query("select b from Batch b order by b.produceDate desc, b.id desc")
    List<Batch> findAllOrdered();

    long countByProduceDate(LocalDate date);

    /**
     * 按生产日期统计批次数与产量（概览折线用，手写 JPQL）。
     * 返回列：生产日期、批次数、产量吨
     */
    @Query("select b.produceDate, count(b), sum(coalesce(b.actualTons, b.plannedTons)) "
            + "from Batch b group by b.produceDate order by b.produceDate")
    List<Object[]> dailyOutput();

    @Query("select count(b) from Batch b where b.status in :statuses")
    long countByStatusIn(@Param("statuses") List<String> statuses);

    /**
     * 发布修订后，把该配方族所有「用着旧版本快照的计划中批次」标记为待选择（默认冻结在原版本）。
     * 已开始投料/制粒/质检的批次不在此列 —— 它们必须保持原版本与原明细。
     * 返回受影响行数（写入修订记录）。
     */
    @Modifying
    @Query("update Batch b set b.migrationStatus = 'PENDING' "
            + "where b.status = 'PLANNED' and b.recipe.id in "
            + "(select r.id from Recipe r where r.recipeCode = :recipeCode and r.status = 'SUPERSEDED')")
    int markPendingForSuperseded(@Param("recipeCode") String recipeCode);

    /**
     * 迁移收口（原子）：只有仍是 PLANNED、仍引用原版本、且全部投料明细都未动的批次才能被改。
     * 返回 0 表示已被并发请求抢先（或批次已开始称量/投料），调用方读取已提交结果后拒绝覆盖。
     */
    @Modifying
    @Query("update Batch b set b.recipe = :toRecipe, b.migrationStatus = 'MIGRATED' "
            + "where b.id = :id and b.status = 'PLANNED' and b.recipe.id = :fromRecipeId "
            + "and not exists (select f.id from FeedingItem f where f.batch.id = b.id and f.status <> 'PENDING')")
    int migrateIfPlanned(@Param("id") Long id,
                         @Param("fromRecipeId") Long fromRecipeId,
                         @Param("toRecipe") Recipe toRecipe);

    /**
     * 冻结收口（原子）：只有仍是 PLANNED、仍引用原版本且尚未做过选择的批次才能被确认冻结。
     * 已冻结/已迁移的批次影响行数=0，后到请求看到已有结果不能覆盖。
     */
    @Modifying
    @Query("update Batch b set b.migrationStatus = 'FROZEN' "
            + "where b.id = :id and b.status = 'PLANNED' and b.recipe.id = :fromRecipeId "
            + "and b.migrationStatus in ('NONE', 'PENDING')")
    int freezeIfPlanned(@Param("id") Long id, @Param("fromRecipeId") Long fromRecipeId);
}
