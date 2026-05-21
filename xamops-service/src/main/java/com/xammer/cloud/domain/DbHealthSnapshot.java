package com.xammer.cloud.domain;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "db_health_snapshot")
public class DbHealthSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "instance_id", nullable = false)
    private String instanceId;

    @Column(nullable = false)
    private int score;

    @Column(nullable = false)
    private String status;

    @Column(name = "issues_json", columnDefinition = "TEXT")
    private String issuesJson;

    @Column(name = "computed_at", nullable = false)
    private LocalDateTime computedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long v) { this.accountId = v; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String v) { this.instanceId = v; }
    public int getScore() { return score; }
    public void setScore(int v) { this.score = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getIssuesJson() { return issuesJson; }
    public void setIssuesJson(String v) { this.issuesJson = v; }
    public LocalDateTime getComputedAt() { return computedAt; }
    public void setComputedAt(LocalDateTime v) { this.computedAt = v; }
}
