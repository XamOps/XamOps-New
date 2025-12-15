package com.xammer.cloud.domain;

import com.xammer.billops.domain.CloudAccount;
import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "agreement")
public class Agreement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    // ✅ FIX: Explicitly map camelCase field to snake_case column
    @Column(name = "s3_key", nullable = false)
    private String s3Key;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(nullable = false)
    private String status; // "DRAFT" or "FINALIZED"

    @Column(name = "uploaded_at")
    private LocalDateTime uploadedAt;

    @Column(name = "finalized_at")
    private LocalDateTime finalizedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cloud_account_id", nullable = false)
    private CloudAccount cloudAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by_user_id", nullable = false)
    private User uploadedBy;

    // Constructors
    public Agreement() {
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getS3Key() {
        return s3Key;
    }

    public void setS3Key(String s3Key) {
        this.s3Key = s3Key;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(LocalDateTime uploadedAt) {
        this.uploadedAt = uploadedAt;
    }

    public LocalDateTime getFinalizedAt() {
        return finalizedAt;
    }

    public void setFinalizedAt(LocalDateTime finalizedAt) {
        this.finalizedAt = finalizedAt;
    }

    public CloudAccount getCloudAccount() {
        return cloudAccount;
    }

    public void setCloudAccount(CloudAccount cloudAccount) {
        this.cloudAccount = cloudAccount;
    }

    public User getUploadedBy() {
        return uploadedBy;
    }

    public void setUploadedBy(User uploadedBy) {
        this.uploadedBy = uploadedBy;
    }
}