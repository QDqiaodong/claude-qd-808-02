package com.feed.mill.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.feed.mill.domain.RecipeRevisionDiff;

public interface RecipeRevisionDiffRepository extends JpaRepository<RecipeRevisionDiff, Long> {

    List<RecipeRevisionDiff> findByRecipeIdOrderByIdAsc(Long recipeId);
}
