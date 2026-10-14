package com.Aryan.APIX.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "apix_local_test_agents", indexes = {
        @Index(name = "idx_apix_agent_user", columnList = "user_id"),
        @Index(name = "uq_apix_agent_credential_hash", columnList = "credential_hash", unique = true)
})
@Getter @Setter @NoArgsConstructor
public class LocalTestAgent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "user_id", nullable = false) private User user;
    @Column(nullable = false, length = 120) private String name;
    @Column(name = "credential_hash", nullable = false, length = 64) private String credentialHash;
    @Column(name = "allowed_targets_json", nullable = false, columnDefinition = "LONGTEXT") private String allowedTargetsJson;
    @Column(nullable = false, length = 20) private String status = "PAIRED";
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "last_seen_at") private LocalDateTime lastSeenAt;
    @Column(name = "revoked_at") private LocalDateTime revokedAt;
}
