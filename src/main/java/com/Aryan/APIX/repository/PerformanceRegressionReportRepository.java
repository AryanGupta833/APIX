package com.Aryan.APIX.repository;

import com.Aryan.APIX.entity.PerformanceRegressionReport;
import com.Aryan.APIX.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface PerformanceRegressionReportRepository extends JpaRepository<PerformanceRegressionReport, Long> {
    Page<PerformanceRegressionReport> findByUserOrderByCreatedAtDesc(User user, Pageable pageable);
    Optional<PerformanceRegressionReport> findByIdAndUser(Long id, User user);
}
