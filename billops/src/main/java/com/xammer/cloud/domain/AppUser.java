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
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@Table(name = "app_user")
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
    private String role; // e.g., "ROLE_USER", "ROLE_ADMIN"

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "users", "cloudAccounts"})
    private Client client;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(length = 20)
    private String contact;

    @Column(length = 100)
    private String designation;

    @Column(name = "all_accounts_access", nullable = false, columnDefinition = "BOOLEAN DEFAULT FALSE")
    private boolean allAccountsAccess = false;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public Client getClient() { return client; }
    public void setClient(Client client) { this.client = client; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public String getContact() { return contact; }
    public void setContact(String contact) { this.contact = contact; }
    public String getDesignation() { return designation; }
    public void setDesignation(String designation) { this.designation = designation; }
    public boolean isAllAccountsAccess() { return allAccountsAccess; }
    public void setAllAccountsAccess(boolean allAccountsAccess) { this.allAccountsAccess = allAccountsAccess; }

    // Constructors
    public AppUser(String username, String password, String email, String role, Client client) {
        this.username = username;
        this.password = password;
        this.email = email;
        this.role = role;
        this.client = client;
    }
}