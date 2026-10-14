package com.Aryan.APIX.repository;

import com.Aryan.APIX.entity.ApiTestSuite;
import com.Aryan.APIX.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ApiTestSuiteRepository extends JpaRepository<ApiTestSuite, Long> {
    Page<ApiTestSuite> findByUserOrderByUpdatedAtDesc(User user, Pageable pageable);
    Optional<ApiTestSuite> findByIdAndUser(Long id, User user);
}
