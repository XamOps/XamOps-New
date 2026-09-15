package com.xammer.cloud.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.time.LocalDateTime;

@Entity
@Table(name = "app_user")
@Data
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    // Nullable: Google (GOOGLE auth_provider) users authenticate via Google and have no local password.
    @Column(nullable = true)
    private String password;

    // How this user authenticates: "LOCAL" (email + password + OTP) or "GOOGLE".
    // Nullable at DB level so `ddl-auto=update` can add it to an existing populated
    // table; the app always sets it on insert (default "LOCAL"), and existing rows
    // (null) are treated as LOCAL by the login code.
    @Column(name = "auth_provider")
    private String authProvider = "LOCAL";

    // Google's permanent unique subject identifier (OpenID "sub"). Null for LOCAL users.
    @Column(name = "google_sub", unique = true, nullable = true)
    private String googleSub;

    // Microsoft (Entra ID) permanent unique subject identifier (OpenID "sub").
    // Null for LOCAL/GOOGLE users. Added by ddl-auto=update like google_sub.
    @Column(name = "microsoft_sub", unique = true, nullable = true)
    private String microsoftSub;

    // --- ✅ MODIFIED THIS LINE ---
    @Column(nullable = false)
    private String role; // e.g., "USER", "ADMIN", "BILLOPS", "XAMOPS"

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    @JsonIgnore
    @lombok.ToString.Exclude
    private Client client;

    public User(String username, String password, Client client) {
        this.username = username;
        this.password = password;
        this.client = client;
        this.role = "USER"; // Default role
    }

    @Column(nullable = true)
    private String email;

    // Mobile number for SMS / WhatsApp notifications. The column already exists on app_user —
    // BillOps has always mapped it as AppUser.contact — it simply was not mapped here, which is
    // why alert and report delivery had no way to reach a phone number.
    //
    // Free text by history: no country code column, no format validation on any write path. Always
    // run it through PhoneNumbers.toE164 before handing it to a provider, and treat null as
    // "this recipient cannot receive mobile notifications".
    @Column(length = 20, nullable = true)
    private String contact;

    @Column(name = "created_by", nullable = true)
    private Long createdBy;

    @Column(name = "is_tour_seen", nullable = false)
    private boolean tourSeen = false;

    @Column(name = "last_login_at", nullable = true)
    private LocalDateTime lastLoginAt;

    public void setEnabled(boolean b) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'setEnabled'");
    }
}