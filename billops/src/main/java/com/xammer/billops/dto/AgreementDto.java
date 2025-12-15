package com.xammer.billops.dto;

import com.xammer.cloud.domain.Agreement;
import java.time.LocalDateTime;

public class AgreementDto {
    private Long id;
    private String fileName;
    private String status;
    private LocalDateTime uploadedAt;
    private LocalDateTime finalizedAt;
    private String downloadUrl;
    private String uploadedBy;
    private Long cloudAccountId;

    // Constructor from Entity
    public static AgreementDto fromEntity(Agreement agreement, String downloadUrl) {
        AgreementDto dto = new AgreementDto();
        dto.setId(agreement.getId());
        dto.setFileName(agreement.getFileName());
        dto.setStatus(agreement.getStatus());
        dto.setUploadedAt(agreement.getUploadedAt());
        dto.setFinalizedAt(agreement.getFinalizedAt());
        dto.setDownloadUrl(downloadUrl);
        dto.setCloudAccountId(agreement.getCloudAccount().getId());

        if (agreement.getUploadedBy() != null) {
            dto.setUploadedBy(agreement.getUploadedBy().getUsername());
        }
        return dto;
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

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public void setDownloadUrl(String downloadUrl) {
        this.downloadUrl = downloadUrl;
    }

    public String getUploadedBy() {
        return uploadedBy;
    }

    public void setUploadedBy(String uploadedBy) {
        this.uploadedBy = uploadedBy;
    }

    public Long getCloudAccountId() {
        return cloudAccountId;
    }

    public void setCloudAccountId(Long cloudAccountId) {
        this.cloudAccountId = cloudAccountId;
    }
}