package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;
import javax.persistence.*;
import java.util.List;

@Entity
@Data
@NoArgsConstructor
public class Client {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String name;

    @Column(nullable = true)
    private String email;

    @Column(nullable = true)
    private Long ownerAdminId;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean starred = false;

    // --- Trial lifecycle (self-signup customers) ---
    // Nullable at DB level so `ddl-auto=update` can add it to an existing populated
    // table. New rows default to PAID via the field initializer; existing rows (null)
    // are treated as non-trial (never gated) by TrialService.
    @Column(name = "plan_type")
    private String planType = "PAID"; // PAID | TRIAL

    @Column(name = "trial_status", nullable = true)
    private String trialStatus; // ACTIVE | EXPIRED | CONVERTED (null for non-trial)

    @Column(name = "trial_start_at", nullable = true)
    private java.time.LocalDateTime trialStartAt;

    @Column(name = "trial_end_at", nullable = true)
    private java.time.LocalDateTime trialEndAt;

    @Column(name = "reminder5_sent_at", nullable = true)
    private java.time.LocalDateTime reminder5SentAt;

    @Column(name = "final_day_sent_at", nullable = true)
    private java.time.LocalDateTime finalDaySentAt;

    // Establishes a one-to-many relationship with users
    @OneToMany(mappedBy = "client", cascade = CascadeType.ALL, orphanRemoval = true)
    @lombok.ToString.Exclude
    private List<User> users;

    // Establishes a one-to-many relationship with cloud accounts
    @OneToMany(mappedBy = "client", cascade = CascadeType.ALL, orphanRemoval = true)
    @lombok.ToString.Exclude
    private List<CloudAccount> cloudAccounts;

    public Client(String name) {
        this.name = name;
    }
}
