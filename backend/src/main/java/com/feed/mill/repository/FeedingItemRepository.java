package com.feed.mill.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.feed.mill.domain.FeedingItem;

/**
 * 投料明细。{@link #batchFeedErrorStats()} 是手写 JPQL 聚合：按批次统计投料误差率。
 */
public interface FeedingItemRepository extends JpaRepository<FeedingItem, Long> {

    @Query("select f from FeedingItem f where f.batch.id = :batchId and f.feedSeq = :seq")
    Optional<FeedingItem> findByBatchAndSeq(@Param("batchId") Long batchId, @Param("seq") Integer seq);

    /**
     * 按批次统计投料误差率与异常条数。
     * 返回列：批次 id、批次号、明细数、平均误差率(绝对值)、最大误差率(绝对值)、异常条数
     */
    @Query("select f.batch.id, f.batch.batchNo, count(f), avg(abs(f.deviationRate)), max(abs(f.deviationRate)), "
            + "sum(case when f.status = 'ABNORMAL' then 1 else 0 end) "
            + "from FeedingItem f where f.actualKg is not null "
            + "group by f.batch.id, f.batch.batchNo order by avg(abs(f.deviationRate)) desc")
    List<Object[]> batchFeedErrorStats();
}
