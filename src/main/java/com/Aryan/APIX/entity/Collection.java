package com.Aryan.APIX.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "collections")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Collection {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    private String name;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate(){
        createdAt=LocalDateTime.now();
    }
}
