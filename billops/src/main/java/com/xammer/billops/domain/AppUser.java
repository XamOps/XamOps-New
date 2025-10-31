package com.xammer.billops.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "app_user") // Explicitly specify the table name to match the existing database
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String role;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "users", "cloudAccounts"})
    private Client client;


    // Constructors
    public AppUser() {
    }

    public AppUser(String username, String password, String email, String role, Client client) {
        this.username = username;
        this.password = password;
        this.email = email;
        this.role = role;
        this.client = client;
    }
}