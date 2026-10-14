package com.Aryan.APIX.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "apix_local_agent_pairings", indexes = {
        @Index(name = "uq_apix_pairing_hash", columnList = "pairing_hash", unique = true),
        @Index(name = "idx_apix_pairing_expiry", columnList = "expires_at")
})
@Getter @Setter @NoArgsConstructor
public class LocalAgentPairing {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "user_id", nullable = false) private User user;
    @Column(name = "pairing_hash", nullable = false, length = 64) private String pairingHash;
    @Column(nullable = false, length = 120) private String agentName;
    @Column(name = "allowed_targets_json", nullable = false, columnDefinition = "LONGTEXT") private String allowedTargetsJson;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "expires_at", nullable = false) private LocalDateTime expiresAt;
    @Column(name = "consumed_at") private LocalDateTime consumedAt;
}
