package com.xammer.cloud.domain;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "fin_ops_report_schedule")
public class FinOpsReportSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "cloud_account_id")
    private CloudAccount cloudAccount;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Frequency frequency;

    // --- NEW FIELD TO MATCH DATABASE ---
    @Column(name = "cron_expression", nullable = false)
    private String cronExpression;

    @Column(name = "is_active")
    private boolean isActive;

    @Column(name = "last_sent")
    private LocalDateTime lastSent;

    public enum Frequency {
        DAILY,
        WEEKLY,
        MONTHLY
    }

    // --- Getters and Setters ---

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public CloudAccount getCloudAccount() {
        return cloudAccount;
    }

    public void setCloudAccount(CloudAccount cloudAccount) {
        this.cloudAccount = cloudAccount;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Frequency getFrequency() {
        return frequency;
    }

    public void setFrequency(Frequency frequency) {
        this.frequency = frequency;
    }

    // --- NEW GETTER AND SETTER ---
    
    public String getCronExpression() {
        return cronExpression;
    }

    public void setCronExpression(String cronExpression) {
        this.cronExpression = cronExpression;
    }
    
    // --- END NEW GETTER AND SETTER ---

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        isActive = active;
    }

    public LocalDateTime getLastSent() {
        return lastSent;
    }

    public void setLastSent(LocalDateTime lastSent) {
        this.lastSent = lastSent;
    }
}