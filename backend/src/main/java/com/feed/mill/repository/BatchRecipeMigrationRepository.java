package com.feed.mill.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.feed.mill.domain.BatchRecipeMigration;

public interface BatchRecipeMigrationRepository extends JpaRepository<BatchRecipeMigration, Long> {

    @Query("select m from BatchRecipeMigration m left join fetch m.fromRecipe left join fetch m.toRecipe "
            + "where m.batch.id = :batchId order by m.createdAt desc, m.id desc")
    List<BatchRecipeMigration> findDetailByBatchId(@Param("batchId") Long batchId);
}
