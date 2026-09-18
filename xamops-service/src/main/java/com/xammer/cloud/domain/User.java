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

    /**
     * Whether this user receives security and WAR email.
     *
     * Defaults TRUE, and the column is NOT NULL DEFAULT TRUE, so every existing user and every
     * user created after this keeps receiving mail until an admin says otherwise. Defaulting the
     * other way would silently stop security delivery across the whole estate on deploy.
     *
     * Narrow by design. It suppresses the WAR onboarding report, security finding notifications
     * and the AWS/GCP alert digests. It does not touch cost or operational email (monthly digest,
     * budget alerts, DB health), login OTP, password reset, invitations, ticket correspondence, or
     * the in-app notification bell — muting a mailbox must not lock anyone out or hide a finding
     * from them inside the product. {@code NotificationRecipientResolver} is the only reader and
     * carries the full reasoning.
     *
     * Not named {@code enabled}: {@link #setEnabled(boolean)} already exists as a stub that
     * throws, and Lombok's generated setter would collide with it.
     *
     * {@code columnDefinition} carries the default for the same reason
     * {@code Client.xamSecureOnboardingEnabled} does. Local runs use
     * {@code ddl-auto=update} with Flyway disabled, and a bare {@code NOT NULL} add against a
     * populated {@code app_user} fails — Hibernate logs that failure as a warning and starts
     * anyway, so the column is absent while every read still selects it. The symptom is not a
     * startup error but every user lookup in the service throwing
     * {@code SQLGrammarException: column email_notifications_enabled does not exist}.
     */
    @Column(name = "email_notifications_enabled", nullable = false,
            columnDefinition = "boolean default true")
    private boolean emailNotificationsEnabled = true;

    public void setEnabled(boolean b) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'setEnabled'");
    }
}