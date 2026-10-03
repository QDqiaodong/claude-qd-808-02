package com.feed.mill.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.feed.mill.domain.Pellet;

/**
 * 制粒工段：一个批次对应一条制粒记录。
 */
public interface PelletRepository extends JpaRepository<Pellet, Long> {

    Optional<Pellet> findByBatchId(Long batchId);

    /** 环模寿命消耗排行（手写 JPQL） */
    @Query("select p from Pellet p order by (p.dieUsedTons / p.dieLifeTons) desc")
    List<Pellet> findByDieWearDesc();
}
