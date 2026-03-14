package com.Aryan.APIX.repository;

import com.Aryan.APIX.entity.CollectionRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.nio.file.LinkOption;
import java.util.List;

public interface CollectionRequestRepository extends JpaRepository<CollectionRequest, Long> {
    List<CollectionRequest> findByCollection_IdOrderByCreatedAtDesc(Long collectionId);
}
