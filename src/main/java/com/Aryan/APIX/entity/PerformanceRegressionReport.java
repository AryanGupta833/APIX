package com.Aryan.APIX.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

@Entity
@Table(name = "performance_regression_reports", indexes = @Index(name = "idx_performance_report_user_created", columnList = "user_id, created_at"))
@Getter @Setter @NoArgsConstructor
public class PerformanceRegressionReport {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "user_id", nullable = false) private User user;
    @Column(nullable = false) private Long baselineId;
    @Column(nullable = false, length = 120) private String baselineName;
    @Column(nullable = false, length = 32) private String status;
    @Column(nullable = false) private int sampleCount;
    @Column(columnDefinition = "TEXT", nullable = false) private String sampleIdsJson;
    @Column(columnDefinition = "TEXT", nullable = false) private String executionIdsJson;
    @Column(columnDefinition = "TEXT", nullable = false) private String traceIdsJson;
    @Column(columnDefinition = "TEXT", nullable = false) private String metricsJson;
    @Column(columnDefinition = "TEXT", nullable = false) private String thresholdsJson;
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;
    @CreationTimestamp @Column(nullable = false, updatable = false) private LocalDateTime createdAt;
}
