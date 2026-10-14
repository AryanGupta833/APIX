package com.Aryan.APIX.repository;

import com.Aryan.APIX.entity.RequestHistory;
import com.Aryan.APIX.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface RequestHistoryRepository extends JpaRepository<RequestHistory,Long> {
    List<RequestHistory> findByUserOrderByCreatedAtDesc(User user);
    List<RequestHistory> findByUserAndUrl(User user, String url);
    List<RequestHistory> findByIdInAndUser(List<Long> ids, User user);
    List<RequestHistory> findByExecutionIdInAndUser(List<String> executionIds, User user);
    Page<RequestHistory> findByUserAndMethodIgnoreCaseAndUrlOrderByCreatedAtDesc(User user, String method, String url, Pageable pageable);

}
