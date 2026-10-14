package com.Aryan.APIX.repository;

import com.Aryan.APIX.entity.LocalAgentPairing;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

public interface LocalAgentPairingRepository extends JpaRepository<LocalAgentPairing, Long> {
    Optional<LocalAgentPairing> findByPairingHash(String pairingHash);
    long countByUser_IdAndConsumedAtIsNullAndExpiresAtAfter(Long userId, LocalDateTime now);

    @Modifying
    @Transactional
    @Query("delete from LocalAgentPairing p where p.expiresAt < :now or p.consumedAt is not null")
    int deleteExpiredOrConsumed(@Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update LocalAgentPairing p set p.consumedAt = :now where p.id = :id and p.consumedAt is null and p.expiresAt > :now")
    int consumeOnce(@Param("id") Long id, @Param("now") LocalDateTime now);
}
