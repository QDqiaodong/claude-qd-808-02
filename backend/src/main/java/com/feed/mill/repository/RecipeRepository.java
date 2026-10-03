package com.feed.mill.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.feed.mill.domain.Recipe;

/**
 * 配方（多修订版本）。{@link #speciesStats()} 是手写 JPQL 聚合：按畜种统计配方数与成本。
 * 并发收口两条线：
 * 1) 唯一索引 uk_recipe_code_rev(recipe_code, revision_no)：同一版本号只落一行；
 *    唯一索引 uk_recipe_active(recipe_code, active_flag)：同一配方族只有一个生效版本；
 * 2) {@link #markSupersededIfReleased} / {@link #markReleasedIfDraft} 条件更新，
 *    多人同时发布修订时只有第一个事务能改到行，后到请求影响行数=0 被拒绝。
 */
public interface RecipeRepository extends JpaRepository<Recipe, Long> {

    List<Recipe> findBySpecies(String species);

    /** 版本链：同一配方编号的全部版本，按版本号升序 */
    List<Recipe> findByRecipeCodeOrderByRevisionNo(String recipeCode);

    /** 按状态找版本（如某配方族的 DRAFT 草稿 / RELEASED 生效版） */
    Optional<Recipe> findFirstByRecipeCodeAndStatus(String recipeCode, String status);

    /** 当前生效版本（active_flag=1 唯一） */
    Optional<Recipe> findFirstByRecipeCodeAndActiveFlag(String recipeCode, Integer activeFlag);

    /** 列表口径：生效版本 + 修订草稿（被取代的历史版本只在版本链里看） */
    @Query("select r from Recipe r where r.status in ('RELEASED', 'DRAFT') order by r.recipeCode, r.revisionNo")
    List<Recipe> findCurrentAndDraft();

    /**
     * 旧版本收口为「被取代」：只有仍是 RELEASED 的行才能被改。
     * 返回 0 表示已被并发发布抢先，调用方据此拒绝。
     */
    @Modifying
    @Query("update Recipe r set r.status = 'SUPERSEDED', r.activeFlag = null "
            + "where r.id = :id and r.status = 'RELEASED'")
    int markSupersededIfReleased(@Param("id") Long id);

    /**
     * 草稿发布为「生效」：只有仍是 DRAFT 的行才能被改（并发重复发布收口）。
     */
    @Modifying
    @Query("update Recipe r set r.status = 'RELEASED', r.activeFlag = 1 "
            + "where r.id = :id and r.status = 'DRAFT'")
    int markReleasedIfDraft(@Param("id") Long id);

    /**
     * 按畜种统计配方数与吨成本（只统计当前生效版本，历史快照不计）。
     * 返回列：畜种、配方数、平均吨成本、最低吨成本、最高吨成本、已发布数
     */
    @Query("select r.species, count(r), avg(r.costPerTon), min(r.costPerTon), max(r.costPerTon), "
            + "sum(case when r.status = 'RELEASED' then 1 else 0 end) "
            + "from Recipe r where r.status = 'RELEASED' group by r.species order by count(r) desc")
    List<Object[]> speciesStats();
}
