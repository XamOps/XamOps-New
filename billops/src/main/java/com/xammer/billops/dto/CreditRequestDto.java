package com.xammer.billops.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class CreditRequestDto {

    /**
     * Supporting document metadata. Deliberately carries no URL: these DTOs are cached both
     * server-side and in the browser, and a presigned URL would expire inside the cache. Callers
     * ask for a fresh link via /credits/{id}/attachments/{index}/url instead.
     */
    public static class AttachmentInfo {
        private int index;
        private String name;
        private String type;
        private Long size;

        public AttachmentInfo() {}

        public AttachmentInfo(int index, String name, String type, Long size) {
            this.index = index;
            this.name = name;
            this.type = type;
            this.size = size;
        }

        public int getIndex() { return index; }
        public void setIndex(int index) { this.index = index; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public Long getSize() { return size; }
        public void setSize(Long size) { this.size = size; }
    }

    private Long id;
    // --- START OF FINAL FIX ---
    private String awsAccountId; // Reverted
    // --- END OF FINAL FIX ---
    private String accountName; // Resolved from cloud_account, null when the id is not linked
    private String provider; // AWS / GCP / AZURE, resolved or inferred from the submitted id
    private BigDecimal expectedCredits;
    private String services;
    private String useCase;
    private String status;
    private String priority; // low / medium / high, null on requests submitted before it was stored
    private Date submittedDate;
    private Long userId;
    // Concrete ArrayList: these DTOs round-trip through the Redis cache, whose serializer writes
    // type information, and Collections.emptyList() does not deserialize cleanly.
    private List<AttachmentInfo> attachments = new ArrayList<>();

    // Default constructor
    public CreditRequestDto() {}

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    // --- START OF FINAL FIX ---
    public String getAwsAccountId() { return awsAccountId; }
    public void setAwsAccountId(String awsAccountId) { this.awsAccountId = awsAccountId; }
    // --- END OF FINAL FIX ---

    public String getAccountName() { return accountName; }
    public void setAccountName(String accountName) { this.accountName = accountName; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public BigDecimal getExpectedCredits() { return expectedCredits; }
    public void setExpectedCredits(BigDecimal expectedCredits) { this.expectedCredits = expectedCredits; }

    public String getServices() { return services; }
    public void setServices(String services) { this.services = services; }

    public String getUseCase() { return useCase; }
    public void setUseCase(String useCase) { this.useCase = useCase; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }

    public Date getSubmittedDate() { return submittedDate; }
    public void setSubmittedDate(Date submittedDate) { this.submittedDate = submittedDate; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public List<AttachmentInfo> getAttachments() { return attachments; }
    public void setAttachments(List<AttachmentInfo> attachments) {
        this.attachments = (attachments != null) ? attachments : new ArrayList<>();
    }
}