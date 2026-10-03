package com.feed.mill.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.feed.mill.common.BizException;
import com.feed.mill.domain.Batch;
import com.feed.mill.domain.Pellet;
import com.feed.mill.dto.PelletAdjustCommand;
import com.feed.mill.repository.BatchRepository;
import com.feed.mill.repository.PelletRepository;

/**
 * 制粒工段：工艺参数调整、环模寿命消耗测算、超寿命不允许继续制粒。
 */
@Service
public class PelletService {

    /** 基准工况：75℃ / 0.30MPa / 3.0mm */
    private static final BigDecimal BASE_TEMP = new BigDecimal("75");
    private static final BigDecimal BASE_PRESSURE = new BigDecimal("0.30");
    private static final BigDecimal BASE_APERTURE = new BigDecimal("3.0");

    private final PelletRepository pelletRepository;
    private final BatchRepository batchRepository;

    public PelletService(PelletRepository pelletRepository, BatchRepository batchRepository) {
        this.pelletRepository = pelletRepository;
        this.batchRepository = batchRepository;
    }

    @Transactional(readOnly = true)
    public List<Pellet> list() {
        return pelletRepository.findByDieWearDesc();
    }

    @Transactional(readOnly = true)
    public Pellet getByBatch(Long batchId) {
        return pelletRepository.findByBatchId(batchId)
                .orElseThrow(() -> new BizException("批次尚未进入制粒工段：batchId=" + batchId));
    }

    /**
     * 调整工艺参数，并给出推荐环模寿命消耗、预计颗粒硬度与粉化率。
     */
    @Transactional
    public Map<String, Object> adjust(Long batchId, PelletAdjustCommand command) {
        Pellet pellet = getByBatch(batchId);
        Batch batch = pellet.getBatch();

        if (command.getTemperingTemp() != null) {
            pellet.setTemperingTemp(command.getTemperingTemp());
        }
        if (command.getSteamPressure() != null) {
            pellet.setSteamPressure(command.getSteamPressure());
        }
        if (command.getDieAperture() != null) {
            pellet.setDieAperture(command.getDieAperture());
        }

        BigDecimal temp = nz(pellet.getTemperingTemp());
        BigDecimal pressure = nz(pellet.getSteamPressure());
        BigDecimal aperture = nz(pellet.getDieAperture());

        BigDecimal wearRate = BigDecimal.ONE
                .add(temp.subtract(BASE_TEMP).multiply(new BigDecimal("0.008")))
                .add(pressure.subtract(BASE_PRESSURE).multiply(new BigDecimal("0.60")))
                .add(aperture.subtract(BASE_APERTURE).multiply(new BigDecimal("0.05")));
        wearRate = clamp(wearRate, new BigDecimal("0.70"), new BigDecimal("1.80"));

        BigDecimal planTons = command.getOutputTons() != null
                ? command.getOutputTons()
                : nz(batch.getPlannedTons());
        BigDecimal recommendWear = planTons.multiply(wearRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal projectedUsed = nz(pellet.getDieUsedTons()).add(recommendWear);
        BigDecimal remaining = nz(pellet.getDieLifeTons()).subtract(projectedUsed);

        pellet.setHardness(estimateHardness(temp, pressure, aperture));
        pellet.setPowderRate(estimatePowderRate(temp, pressure, aperture));
        pelletRepository.save(pellet);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pellet", pellet);
        result.put("wearRate", wearRate.setScale(3, RoundingMode.HALF_UP));
        result.put("recommendWearTons", recommendWear);
        result.put("projectedUsedTons", projectedUsed.setScale(2, RoundingMode.HALF_UP));
        result.put("remainingTons", remaining.setScale(2, RoundingMode.HALF_UP));
        result.put("overLife", remaining.compareTo(BigDecimal.ZERO) < 0);
        result.put("message", remaining.compareTo(BigDecimal.ZERO) < 0
                ? "按当前参数制粒将超出环模寿命 " + remaining.abs().setScale(2, RoundingMode.HALF_UP) + " 吨，请更换环模或降低产量"
                : "按当前参数预计消耗环模寿命 " + recommendWear + " 吨，剩余可用 " + remaining.setScale(2, RoundingMode.HALF_UP) + " 吨");
        return result;
    }

    /**
     * 本批制粒产出：环模累计产量超过寿命上限不允许继续制粒。
     */
    @Transactional
    public Pellet produce(Long batchId, BigDecimal outputTons) {
        Pellet pellet = getByBatch(batchId);
        BigDecimal out = outputTons == null ? nz(pellet.getBatch().getPlannedTons()) : outputTons;
        if (out.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("制粒产量必须大于 0");
        }
        BigDecimal used = nz(pellet.getDieUsedTons()).add(out);
        BigDecimal life = nz(pellet.getDieLifeTons());
        if (used.compareTo(life) > 0) {
            pellet.setStatus("BLOCKED");
            pelletRepository.save(pellet);
            throw new BizException("环模 " + pellet.getDieCode() + " 累计产量将达 " + used.setScale(2, RoundingMode.HALF_UP)
                    + " 吨，超过寿命上限 " + life + " 吨，不允许继续制粒，请先更换环模");
        }
        pellet.setDieUsedTons(used.setScale(2, RoundingMode.HALF_UP));
        pellet.setOutputTons(nz(pellet.getOutputTons()).add(out).setScale(2, RoundingMode.HALF_UP));
        pellet.setStatus("DONE");

        Batch batch = pellet.getBatch();
        batch.setActualTons(pellet.getOutputTons());
        batch.setStatus("QC");
        batchRepository.save(batch);
        return pelletRepository.save(pellet);
    }

    private BigDecimal estimateHardness(BigDecimal temp, BigDecimal pressure, BigDecimal aperture) {
        BigDecimal value = new BigDecimal("25")
                .add(temp.subtract(new BigDecimal("70")).multiply(new BigDecimal("0.6")))
                .add(pressure.multiply(new BigDecimal("30")))
                .add(new BigDecimal("6").subtract(aperture).multiply(new BigDecimal("1.5")));
        return clamp(value, new BigDecimal("15"), new BigDecimal("70")).setScale(1, RoundingMode.HALF_UP);
    }

    private BigDecimal estimatePowderRate(BigDecimal temp, BigDecimal pressure, BigDecimal aperture) {
        BigDecimal value = new BigDecimal("12")
                .subtract(temp.subtract(new BigDecimal("70")).multiply(new BigDecimal("0.15")))
                .subtract(pressure.multiply(new BigDecimal("8")))
                .add(aperture.multiply(new BigDecimal("0.8")));
        return clamp(value, new BigDecimal("3"), new BigDecimal("15")).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal clamp(BigDecimal value, BigDecimal min, BigDecimal max) {
        if (value.compareTo(min) < 0) {
            return min;
        }
        return value.compareTo(max) > 0 ? max : value;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
