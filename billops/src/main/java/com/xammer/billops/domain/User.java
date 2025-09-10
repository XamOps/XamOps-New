package com.xammer.billops.domain;

import lombok.Data;
import lombok.NoArgsConstructor;
import javax.persistence.*; // Changed from jakarta.persistence

@Entity
@Table(name = "app_users") // Renamed table to avoid conflict with 'user' keyword
@Data
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String role;
}