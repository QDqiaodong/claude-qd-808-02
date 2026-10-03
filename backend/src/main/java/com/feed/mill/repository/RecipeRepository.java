package com.feed.mill.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.feed.mill.domain.Recipe;

import jakarta.persistence.LockModeType;

/**
 * 配方修订版本。
 */
public interface RecipeRepository extends JpaRepository<Recipe, Long> {

    @Query("select r from Recipe r left join fetch r.items i left join fetch i.material "
            + "where r.id = :id")
    Optional<Recipe> findDetailById(@Param("id") Long id);

    /** 发布/创建修订时锁住配方族，串行化同一配方的并发修订动作 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r.id from Recipe r where r.familyKey = :familyKey")
    List<Long> lockFamily(@Param("familyKey") String familyKey);

    Optional<Recipe> findByFamilyKeyAndCurrentFlag(String familyKey, Integer currentFlag);

    Optional<Recipe> findByFamilyKeyAndActiveDraftFlag(String familyKey, Integer activeDraftFlag);

    List<Recipe> findByFamilyKeyOrderByVersionNoDesc(String familyKey);

    @Query("select distinct r.familyKey from Recipe r")
    List<String> findFamilyKeys();

    /**
     * 按畜种统计配方族数与当前版本成本，避免同一配方多个修订版本被重复计数。
     */
    @Query("select r.species, count(distinct r.familyKey), avg(r.costPerTon), min(r.costPerTon), max(r.costPerTon), "
            + "sum(case when r.status = 'RELEASED' then 1 else 0 end) "
            + "from Recipe r where r.currentFlag = 1 group by r.species order by count(distinct r.familyKey) desc")
    List<Object[]> speciesStats();
}
