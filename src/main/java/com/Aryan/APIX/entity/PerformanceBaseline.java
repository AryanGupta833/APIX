package com.Aryan.APIX.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

@Entity
@Table(name = "performance_baselines", indexes = @Index(name = "idx_performance_baseline_user_created", columnList = "user_id, created_at"))
@Getter @Setter @NoArgsConstructor
public class PerformanceBaseline {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "user_id", nullable = false) private User user;
    @Column(nullable = false, length = 120) private String name;
    @Column(nullable = false, length = 16) private String method;
    @Column(nullable = false, columnDefinition = "TEXT") private String url;
    @Column(nullable = false, length = 64) private String scenarioFingerprint;
    @Column(nullable = false) private int sampleCount;
    @Column(columnDefinition = "TEXT", nullable = false) private String sampleIdsJson;
    @Column(columnDefinition = "TEXT", nullable = false) private String metricsJson;
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;
    @Column(nullable = false) private boolean active = true;
    @CreationTimestamp @Column(nullable = false, updatable = false) private LocalDateTime createdAt;
}
