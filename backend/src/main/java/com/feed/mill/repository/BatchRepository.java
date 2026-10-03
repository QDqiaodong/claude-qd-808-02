package com.feed.mill.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.feed.mill.domain.Batch;

/**
 * 生产批次。
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
}
