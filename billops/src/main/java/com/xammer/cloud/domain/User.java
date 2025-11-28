package com.xammer.cloud.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

import com.xammer.billops.domain.Client;
import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.security.core.GrantedAuthority; // --- IMPORT ---
import org.springframework.security.core.authority.SimpleGrantedAuthority; // --- IMPORT ---
import org.springframework.security.core.userdetails.UserDetails; // --- IMPORT ---

import java.util.Collection; // --- IMPORT ---
import java.util.Collections; // --- IMPORT ---

@Entity
@Table(name = "app_user")
@Data
@NoArgsConstructor
public class User implements UserDetails { // --- IMPLEMENT UserDetails ---

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    private String password;

    // --- START: ADDED EMAIL FIELD ---
    @Column(nullable = false)
    private String email;
    // --- END: ADDED EMAIL FIELD ---

    @Column(nullable = false)
    private String role; // e.g., "ROLE_USER", "ROLE_ADMIN", "ROLE_BILLOPS", "ROLE_XAMOPS", "ROLE_BILLOPS_ADMIN"

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    @JsonIgnore
    private Client client;

    public User(String username, String password, Client client) {
        this.username = username;
        this.password = password;
        this.client = client;
        this.role = "ROLE_USER";
    }

    // --- Manually added getter/setter for email ---
    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    // --- START: IMPLEMENTED UserDetails METHODS ---
    @Override
    @JsonIgnore
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return Collections.singletonList(new SimpleGrantedAuthority(this.role));
    }

    @Override
    public String getPassword() {
        return this.password;
    }

    @Override
    public String getUsername() {
        return this.username;
    }

    @Override
    @JsonIgnore
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    @JsonIgnore
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    @JsonIgnore
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    @JsonIgnore
    public boolean isEnabled() {
        return true;
    }

    public void setEnabled(boolean b) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'setEnabled'");
    }
    // --- END: IMPLEMENTED UserDetails METHODS ---
}