package com.Aryan.APIX.repository;

import com.Aryan.APIX.entity.RequestHistory;
import com.Aryan.APIX.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RequestHistoryRepository extends JpaRepository<RequestHistory,Long> {
    List<RequestHistory> findByUserOrderByCreatedAtDesc(User user);
    List<RequestHistory> findByUserAndUrl(User user, String url);

}
