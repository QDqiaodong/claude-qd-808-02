package com.feed.mill.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;

import com.feed.mill.domain.Batch;

import jakarta.persistence.LockModeType;

/**
 * 生产批次。
 */
public interface BatchRepository extends JpaRepository<Batch, Long> {

    @Query("select b from Batch b order by b.produceDate desc, b.id desc")
    List<Batch> findAllOrdered();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b.id from Batch b where b.recipe.id = :recipeId and b.status = 'PLANNED'")
    List<Long> lockPlannedByRecipe(@Param("recipeId") Long recipeId);

    @Query("select b from Batch b left join fetch b.feedings f left join fetch f.material "
            + "where b.id = :id")
    Optional<Batch> findDetailById(@Param("id") Long id);

    /** 迁移决策时锁定批次行；配合 @Version 保证重复点击/并发提交只有一次结果生效 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b.id from Batch b where b.id = :id")
    Optional<Long> lockDetailForUpdate(@Param("id") Long id);

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
}
