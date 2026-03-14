package com.Aryan.APIX.repository;

import com.Aryan.APIX.entity.RequestHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HistoryRepository extends JpaRepository<RequestHistory,Long> {
    List<RequestHistory> findTop20ByUrlOrderByCreatedAtDesc(String url);
    List<RequestHistory> findByUrl(String url);
}
