package com.xammer.cloud.domain;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xammer.billops.domain.Client;

import lombok.Data;

@Entity
@Data
@Table(name = "app_user") // Keeps connection to the existing database table
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