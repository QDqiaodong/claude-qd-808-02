package com.feed.mill.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.feed.mill.common.BizException;
import com.feed.mill.domain.Material;
import com.feed.mill.repository.MaterialRepository;

/**
 * 原料与筒仓：库存、水分、单价、安全库存预警与投料扣减。
 */
@Service
public class MaterialService {

    private final MaterialRepository materialRepository;

    public MaterialService(MaterialRepository materialRepository) {
        this.materialRepository = materialRepository;
    }

    @Transactional(readOnly = true)
    public List<Material> list() {
        return materialRepository.findAll();
    }

    /** 低于安全库存的原料（缺料预警） */
    @Transactional(readOnly = true)
    public List<Material> lowStock() {
        return materialRepository.findLowStock();
    }

    @Transactional(readOnly = true)
    public Material get(Long id) {
        return materialRepository.findById(id)
                .orElseThrow(() -> new BizException("原料不存在：id=" + id));
    }

    /** 筒仓总览：总库存吨、库存金额、缺料品种数 */
    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        List<Material> all = materialRepository.findAll();
        BigDecimal totalTons = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Material m : all) {
            BigDecimal stock = nz(m.getStockTons());
            totalTons = totalTons.add(stock);
            totalAmount = totalAmount.add(stock.multiply(nz(m.getUnitPrice())));
        }
        return Map.of(
                "materialCount", all.size(),
                "lowStockCount", materialRepository.findLowStock().size(),
                "totalTons", totalTons.setScale(2, RoundingMode.HALF_UP),
                "totalAmount", totalAmount.setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * 投料扣减库存（kg 入参，按吨换算）。
     */
    @Transactional
    public void deductStock(Long materialId, BigDecimal kg) {
        Material material = get(materialId);
        BigDecimal needTons = nz(kg).divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP);
        BigDecimal stock = nz(material.getStockTons());
        if (stock.compareTo(needTons) < 0) {
            throw new BizException("原料 " + material.getName() + " 库存不足：需 " + needTons + " 吨，筒仓现存 "
                    + stock + " 吨，请先补料");
        }
        material.setStockTons(stock.subtract(needTons).setScale(2, RoundingMode.HALF_UP));
        materialRepository.save(material);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
