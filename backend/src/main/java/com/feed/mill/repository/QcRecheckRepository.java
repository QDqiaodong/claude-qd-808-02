package com.feed.mill.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.feed.mill.domain.QcRecheck;

/**
 * 退货复检单。
 * 并发收口两条线：
 * 1) 建单：数据库唯一索引 (batch_id, active_flag)，一批次只允许一张进行中；
 * 2) 盖章：{@link #closeIfActive} 条件更新，只有仍处进行中的那张单能被盖上最终结论。
 */
public interface QcRecheckRepository extends JpaRepository<QcRecheck, Long> {

    /** 当前进行中的复检单（active_flag=1 唯一） */
    Optional<QcRecheck> findFirstByBatch_IdAndActiveFlagOrderByIdDesc(Long batchId, Integer activeFlag);

    long countBySample_Id(Long sampleId);

    List<QcRecheck> findByStatus(String status);

    /** 按 id 升序：统计口径叠加时，后完成的复检单覆盖先完成的 */
    List<QcRecheck> findByStatusOrderById(String status);

    List<QcRecheck> findAllByOrderByIdDesc();

    /** 复核员接手：仅当尚未有复核员且仍在进行中 */
    @Modifying
    @Query("update QcRecheck r set r.reviewer = :reviewer "
            + "where r.id = :id and r.reviewer is null and r.activeFlag = 1")
    int claimReviewer(@Param("id") Long id, @Param("reviewer") String reviewer);

    /**
     * 最终结论盖章（原子）：只有进行中的单才能被关闭。
     * 返回 0 表示已被并发请求先完成，调用方据此拒绝覆盖。
     */
    @Modifying
    @Query("update QcRecheck r set r.status = 'DONE', r.activeFlag = null, "
            + "r.finalConclusion = :conclusion, r.finalDisposition = :disposition, "
            + "r.reviewedAt = :reviewedAt, r.remark = :remark "
            + "where r.id = :id and r.activeFlag = 1")
    int closeIfActive(@Param("id") Long id,
                      @Param("conclusion") String conclusion,
                      @Param("disposition") String disposition,
                      @Param("reviewedAt") LocalDateTime reviewedAt,
                      @Param("remark") String remark);
}
