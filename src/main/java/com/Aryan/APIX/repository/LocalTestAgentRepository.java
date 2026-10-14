package com.Aryan.APIX.repository;

import com.Aryan.APIX.entity.LocalTestAgent;
import com.Aryan.APIX.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LocalTestAgentRepository extends JpaRepository<LocalTestAgent, Long> {
    List<LocalTestAgent> findByUserOrderByCreatedAtDesc(User user);
    Optional<LocalTestAgent> findByIdAndUser(Long id, User user);
    Optional<LocalTestAgent> findByCredentialHash(String hash);
    List<LocalTestAgent> findByStatus(String status);
    long countByUser_IdAndStatus(Long userId, String status);
}
