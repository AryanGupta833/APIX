package com.Aryan.APIX.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "collection_requests")
public class CollectionRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @CreationTimestamp
    private LocalDateTime createdAt;


    private String method;

    @Column(columnDefinition = "TEXT")
    private String url;
    @Column(columnDefinition = "TEXT")
    private String headers;
    @Column(columnDefinition = "TEXT")
    private String body;
    @Column(columnDefinition = "TEXT")
    private String queryParams;
    private String authType;
    @Column(columnDefinition = "TEXT")
    private String authValue;
    private Boolean diagnosticMode;
    @ManyToOne
    @JoinColumn(name = "collection_id")
    private Collection collection;
}
