package com.Aryan.APIX.repository;

import com.Aryan.APIX.entity.ApiSuiteRun;
import com.Aryan.APIX.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

public interface ApiSuiteRunRepository extends JpaRepository<ApiSuiteRun, Long> {
    Page<ApiSuiteRun> findBySuiteIdAndUserOrderByStartedAtDesc(Long suiteId, User user, Pageable pageable);
    Optional<ApiSuiteRun> findByIdAndUser(Long id, User user);
    List<ApiSuiteRun> findByStatusIn(List<String> statuses);
    long countByUserAndStatusIn(User user, List<String> statuses);
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update ApiSuiteRun r set r.status = 'RUNNING', r.startedAt = :startedAt, r.version = coalesce(r.version, 0) + 1 where r.id = :id and r.status = 'QUEUED'")
    int claimQueued(@Param("id") Long id, @Param("startedAt") java.time.LocalDateTime startedAt);
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update ApiSuiteRun r set r.status = 'CANCELLED', r.completedAt = :completedAt, r.durationMs = 0, r.failureSummary = 'Cancelled before execution started', r.version = coalesce(r.version, 0) + 1 where r.id = :id and r.status = 'QUEUED'")
    int cancelQueued(@Param("id") Long id, @Param("completedAt") java.time.LocalDateTime completedAt);
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update ApiSuiteRun r set r.status = 'CANCEL_REQUESTED', r.version = coalesce(r.version, 0) + 1 where r.id = :id and r.status = 'RUNNING'")
    int requestRunningCancellation(@Param("id") Long id);
}
