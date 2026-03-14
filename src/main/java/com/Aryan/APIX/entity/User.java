package com.Aryan.APIX.entity;


import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.scheduling.annotation.Scheduled;

@Entity
@Table(name = "users")

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    @Column(unique = true)
    private String email;
    private String provider;
    private String providerId;

    public User(String name, String email, String google, String email1) {
        this.email=email;
        this.name=name;
        this.provider=google;
        this.providerId=provider;
    }
}
