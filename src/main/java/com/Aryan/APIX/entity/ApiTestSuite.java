package com.Aryan.APIX.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;

@Entity
@Table(name = "api_test_suites", indexes = @Index(name = "idx_api_suite_user_updated", columnList = "user_id, updated_at"))
@Getter @Setter @NoArgsConstructor
public class ApiTestSuite {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "user_id", nullable = false) private User user;
    @Column(nullable = false, length = 120) private String name;
    @Column(length = 1000) private String description;
    @Column(columnDefinition = "LONGTEXT", nullable = false) private String casesJson;
    @Column(nullable = false, length = 32) private String failurePolicy;
    @CreationTimestamp @Column(nullable = false, updatable = false) private LocalDateTime createdAt;
    @UpdateTimestamp @Column(nullable = false) private LocalDateTime updatedAt;
}
