package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
public class FinOpsReportSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String frequency; // "DAILY", "WEEKLY", "MONTHLY"

    @Column(nullable = false)
    private String cronExpression;

    @Column(nullable = false)
    private boolean isActive = true;

    private LocalDateTime lastSent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cloud_account_id", nullable = false)
    private CloudAccount cloudAccount;

    public FinOpsReportSchedule(String email, String frequency, String cronExpression, User user, CloudAccount cloudAccount) {
        this.email = email;
        this.frequency = frequency;
        this.cronExpression = cronExpression;
        this.user = user;
        this.cloudAccount = cloudAccount;
    }
}