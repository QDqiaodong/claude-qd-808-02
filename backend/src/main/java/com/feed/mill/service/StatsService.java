package com.feed.mill.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feed.mill.dto.DailyOutputVO;
import com.feed.mill.dto.DashboardVO;
import com.feed.mill.repository.BatchRepository;
import com.feed.mill.repository.MaterialRepository;

/**
 * 工段概览：今日批次、累计产量、合格率、近 7 日产量折线。概览结果在 Redis 缓存 30 秒。
 */
@Service
public class StatsService {

    private static final String CACHE_KEY = "feed-mill:dashboard";
    private static final int TREND_DAYS = 7;

    private final BatchRepository batchRepository;
    private final MaterialRepository materialRepository;
    private final QcService qcService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public StatsService(BatchRepository batchRepository,
                        MaterialRepository materialRepository,
                        QcService qcService,
                        StringRedisTemplate redisTemplate,
                        ObjectMapper objectMapper) {
        this.batchRepository = batchRepository;
        this.materialRepository = materialRepository;
        this.qcService = qcService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public DashboardVO dashboard() {
        String cached = readCache();
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, DashboardVO.class);
            } catch (Exception ignore) {
                // 缓存解析失败就重算
            }
        }

        DashboardVO vo = new DashboardVO();
        List<Object[]> daily = batchRepository.dailyOutput();

        LocalDate today = LocalDate.now();
        long todayCount = batchRepository.countByProduceDate(today);
        if (todayCount == 0 && !daily.isEmpty()) {
            LocalDate latest = null;
            for (Object[] row : daily) {
                LocalDate date = (LocalDate) row[0];
                if (date != null && (latest == null || date.isAfter(latest))) {
                    latest = date;
                }
            }
            if (latest != null) {
                today = latest;
                todayCount = batchRepository.countByProduceDate(latest);
            }
        }
        vo.setTodayBatchCount(todayCount);

        BigDecimal cumulative = BigDecimal.ZERO;
        for (Object[] row : daily) {
            cumulative = cumulative.add(toBigDecimal(row[2]));
        }
        vo.setCumulativeOutputTons(cumulative.setScale(2, RoundingMode.HALF_UP));

        Map<String, Long> conclusion = qcService.conclusionStats();
        long total = 0;
        long pass = 0;
        for (Map.Entry<String, Long> entry : conclusion.entrySet()) {
            total += entry.getValue();
            if ("PASS".equals(entry.getKey())) {
                pass = entry.getValue();
            }
        }
        vo.setPassRate(total == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(pass).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP));

        vo.setRunningCount(batchRepository.countByStatusIn(List.of("FEEDING", "PELLETING", "QC", "RECHECK")));
        vo.setLowStockCount((long) materialRepository.findLowStock().size());

        int from = Math.max(0, daily.size() - TREND_DAYS);
        List<DailyOutputVO> trend = new ArrayList<>();
        for (int i = from; i < daily.size(); i++) {
            Object[] row = daily.get(i);
            trend.add(new DailyOutputVO((LocalDate) row[0], toLong(row[1]), toBigDecimal(row[2])));
        }
        vo.setTrend(trend);

        writeCache(vo);
        return vo;
    }

    private String readCache() {
        try {
            return redisTemplate.opsForValue().get(CACHE_KEY);
        } catch (Exception e) {
            return null;
        }
    }

    private void writeCache(DashboardVO vo) {
        try {
            redisTemplate.opsForValue().set(CACHE_KEY, objectMapper.writeValueAsString(vo), Duration.ofSeconds(30));
        } catch (Exception ignored) {
            // Redis 不可用时降级为直查
        }
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value instanceof BigDecimal b ? b : new BigDecimal(value.toString());
    }

    private static Long toLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }
}
