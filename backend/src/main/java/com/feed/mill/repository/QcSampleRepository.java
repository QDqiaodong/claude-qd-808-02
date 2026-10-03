package com.feed.mill.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.feed.mill.domain.QcSample;

/**
 * 质检抽样。{@link #failDistribution()} 是手写 JPQL 聚合：质检不合格项分布。
 */
public interface QcSampleRepository extends JpaRepository<QcSample, Long> {

    /**
     * 检验项不合格分布。
     * 返回列：检验项编码、检验项名称、检验次数、不合格次数
     */
    @Query("select i.itemCode, i.itemName, count(i), sum(case when i.qualified = false then 1 else 0 end) "
            + "from QcSample s join s.items i "
            + "group by i.itemCode, i.itemName "
            + "order by sum(case when i.qualified = false then 1 else 0 end) desc, i.itemCode")
    List<Object[]> failDistribution();

    /** 结论分布：合格/降级/退货 各多少批（手写 JPQL） */
    @Query("select s.conclusion, count(s) from QcSample s where s.conclusion is not null group by s.conclusion")
    List<Object[]> conclusionDistribution();
}
