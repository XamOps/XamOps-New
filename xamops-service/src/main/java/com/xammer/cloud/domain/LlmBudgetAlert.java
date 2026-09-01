package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "llm_budget_alert", indexes = {
    @Index(name = "idx_llm_budget_alert_budget", columnList = "budget_id"),
    @Index(name = "idx_llm_budget_alert_client", columnList = "client_id"),
    @Index(name = "idx_llm_budget_alert_month", columnList = "budget_month")
})
@Data
@NoArgsConstructor
public class LlmBudgetAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "budget_id", nullable = false)
    private Long budgetId;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "threshold_pct", nullable = false)
    private Integer thresholdPct;

    @Column(name = "actual_spend_usd", nullable = false)
    private Double actualSpendUsd;

    @Column(name = "budget_limit_usd", nullable = false)
    private Double budgetLimitUsd;

    @Column(name = "budget_month", nullable = false, length = 7)
    private String budgetMonth;

    @Column(name = "triggered_at", nullable = false)
    private Instant triggeredAt;

    @Column(nullable = false)
    private Boolean dismissed = false;

    @Column(name = "email_sent", nullable = false)
    private Boolean emailSent = false;

    @PrePersist
    protected void onCreate() {
        if (triggeredAt == null) triggeredAt = Instant.now();
    }
}
