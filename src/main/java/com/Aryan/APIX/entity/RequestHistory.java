package com.Aryan.APIX.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "request_history")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class RequestHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    private String method;
    @Column(columnDefinition = "TEXT")
    private String url;

    private int statusCode;
    private long responseTime;

    @Column(length = 36)
    private String executionId;

    @Column(length = 32)
    private String traceId;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String headers;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String body;

    @PrePersist
    protected void onCreate(){

    }

    @CreationTimestamp
    private LocalDateTime createdAt;
}
