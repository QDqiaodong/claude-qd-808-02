package com.feed.mill.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.feed.mill.domain.BatchMigration;

/**
 * 批次配方迁移/冻结记录。
 */
public interface BatchMigrationRepository extends JpaRepository<BatchMigration, Long> {

    /** 某批次的迁移/冻结历史，新记录在前 */
    List<BatchMigration> findByBatchIdOrderByIdDesc(Long batchId);

    /** 全部迁移/冻结记录，新记录在前 */
    List<BatchMigration> findAllByOrderByIdDesc();
}
