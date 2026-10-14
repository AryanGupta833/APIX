package com.Aryan.APIX.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Table(name = "api_suite_runs", indexes = @Index(name = "idx_api_suite_run_user_started", columnList = "user_id, started_at"))
@Getter @Setter @NoArgsConstructor
public class ApiSuiteRun {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "user_id", nullable = false) private User user;
    @Column(nullable = false) private Long suiteId;
    @Column(nullable = false, length = 120) private String suiteName;
    @Column(nullable = false, length = 24) private String status;
    @Column(nullable = false) private int passed;
    @Column(nullable = false) private int failed;
    @Column(nullable = false) private int skipped;
    @Column(nullable = false) private int errored;
    @Column(nullable = false) private int totalCases;
    @Column(nullable = false) private int completedCases;
    @Column(length = 100) private String currentCaseId;
    @Column(length = 200) private String currentCaseName;
    @Column(nullable = false, columnDefinition = "LONGTEXT") private String resultsJson = "[]";
    @Column(nullable = false) private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Long durationMs;
    @Column(length = 2000) private String failureSummary;
    @Version @Column(nullable = false) private Long version;
}
