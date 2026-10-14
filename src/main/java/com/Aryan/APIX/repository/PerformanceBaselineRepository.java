package com.Aryan.APIX.repository;

import com.Aryan.APIX.entity.PerformanceBaseline;
import com.Aryan.APIX.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface PerformanceBaselineRepository extends JpaRepository<PerformanceBaseline, Long> {
    Page<PerformanceBaseline> findByUserAndActiveTrueOrderByCreatedAtDesc(User user, Pageable pageable);
    Optional<PerformanceBaseline> findByIdAndUserAndActiveTrue(Long id, User user);
}
