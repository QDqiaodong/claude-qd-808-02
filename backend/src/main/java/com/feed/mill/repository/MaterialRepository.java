package com.feed.mill.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.feed.mill.domain.Material;

/**
 * 原料与筒仓。
 */
public interface MaterialRepository extends JpaRepository<Material, Long> {

    Optional<Material> findByMaterialCode(String materialCode);

    /** 低于安全库存的原料（手写 JPQL） */
    @Query("select m from Material m where m.stockTons < m.safetyStock order by m.materialCode")
    List<Material> findLowStock();
}
