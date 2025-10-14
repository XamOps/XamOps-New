package com.xammer.billops.dto;

import java.math.BigDecimal;
import java.util.Date;

public class CreditRequestDto {
    private Long id;
    // --- START OF FINAL FIX ---
    private String awsAccountId; // Reverted
    // --- END OF FINAL FIX ---
    private BigDecimal expectedCredits;
    private String services;
    private String useCase;
    private String status;
    private Date submittedDate;
    private Long userId;

    // Default constructor
    public CreditRequestDto() {}

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    // --- START OF FINAL FIX ---
    public String getAwsAccountId() { return awsAccountId; }
    public void setAwsAccountId(String awsAccountId) { this.awsAccountId = awsAccountId; }
    // --- END OF FINAL FIX ---

    public BigDecimal getExpectedCredits() { return expectedCredits; }
    public void setExpectedCredits(BigDecimal expectedCredits) { this.expectedCredits = expectedCredits; }

    public String getServices() { return services; }
    public void setServices(String services) { this.services = services; }

    public String getUseCase() { return useCase; }
    public void setUseCase(String useCase) { this.useCase = useCase; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Date getSubmittedDate() { return submittedDate; }
    public void setSubmittedDate(Date submittedDate) { this.submittedDate = submittedDate; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
}