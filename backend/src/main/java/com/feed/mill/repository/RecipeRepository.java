package com.feed.mill.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.feed.mill.domain.Recipe;

/**
 * 配方。{@link #speciesStats()} 是手写 JPQL 聚合：按畜种统计配方数与成本。
 */
public interface RecipeRepository extends JpaRepository<Recipe, Long> {

    List<Recipe> findBySpecies(String species);

    /**
     * 按畜种统计配方数与吨成本。
     * 返回列：畜种、配方数、平均吨成本、最低吨成本、最高吨成本、已发布数
     */
    @Query("select r.species, count(r), avg(r.costPerTon), min(r.costPerTon), max(r.costPerTon), "
            + "sum(case when r.status = 'RELEASED' then 1 else 0 end) "
            + "from Recipe r group by r.species order by count(r) desc")
    List<Object[]> speciesStats();
}
