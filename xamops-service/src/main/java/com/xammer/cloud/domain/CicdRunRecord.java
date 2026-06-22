package com.xammer.cloud.domain;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "cicd_run_record",
       uniqueConstraints = @UniqueConstraint(columnNames = {"config_id", "run_id", "provider"}))
public class CicdRunRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String provider; // "GITHUB" or "JENKINS"

    @Column(name = "config_id", nullable = false)
    private Long configId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String workflowName;

    private String repoOwner;  // GitHub only

    private String repoName;   // GitHub only

    @Column(name = "run_id", nullable = false)
    private String runId;

    private Integer runNumber;

    @Column(nullable = false)
    private String status; // "success", "failure", "cancelled"

    private String branch;

    private Long durationSeconds;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant completedAt;

    @Column(nullable = false)
    private boolean isDeployment;

    private String failedStage;

    @Column(name = "failure_category", length = 30)
    private String failureCategory;

    @Column(name = "notification_sent", nullable = false)
    private boolean notificationSent = false;

    @Column(columnDefinition = "TEXT")
    private String htmlUrl;

    // --- Constructors ---
    public CicdRunRecord() {}

    // --- Getters and Setters ---
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public Long getConfigId() {
        return configId;
    }

    public void setConfigId(Long configId) {
        this.configId = configId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getWorkflowName() {
        return workflowName;
    }

    public void setWorkflowName(String workflowName) {
        this.workflowName = workflowName;
    }

    public String getRepoOwner() {
        return repoOwner;
    }

    public void setRepoOwner(String repoOwner) {
        this.repoOwner = repoOwner;
    }

    public String getRepoName() {
        return repoName;
    }

    public void setRepoName(String repoName) {
        this.repoName = repoName;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public Integer getRunNumber() {
        return runNumber;
    }

    public void setRunNumber(Integer runNumber) {
        this.runNumber = runNumber;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public Long getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Long durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public boolean isDeployment() {
        return isDeployment;
    }

    public void setDeployment(boolean deployment) {
        isDeployment = deployment;
    }

    public String getFailedStage() {
        return failedStage;
    }

    public void setFailedStage(String failedStage) {
        this.failedStage = failedStage;
    }

    public String getFailureCategory() { return failureCategory; }
    public void setFailureCategory(String failureCategory) { this.failureCategory = failureCategory; }

    public boolean isNotificationSent() { return notificationSent; }
    public void setNotificationSent(boolean notificationSent) { this.notificationSent = notificationSent; }

    public String getHtmlUrl() {
        return htmlUrl;
    }

    public void setHtmlUrl(String htmlUrl) {
        this.htmlUrl = htmlUrl;
    }
}
