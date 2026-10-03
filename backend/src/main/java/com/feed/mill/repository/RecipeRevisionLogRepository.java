package com.feed.mill.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.feed.mill.domain.RecipeRevisionLog;

/**
 * 配方修订记录（含修订前后差异快照）。
 */
public interface RecipeRevisionLogRepository extends JpaRepository<RecipeRevisionLog, Long> {

    /** 某配方族的修订历史，新记录在前 */
    List<RecipeRevisionLog> findByRecipeCodeOrderByIdDesc(String recipeCode);

    /** 全部修订历史，新记录在前 */
    List<RecipeRevisionLog> findAllByOrderByIdDesc();
}
