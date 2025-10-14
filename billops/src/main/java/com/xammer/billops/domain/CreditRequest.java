package com.xammer.billops.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.Date;

@Entity
@Table(name = "credit_requests")
public class CreditRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // --- START OF FINAL FIX ---
    @Column(name = "aws_account_id", nullable = false)
    private String awsAccountId; // Reverted to match the database column
    // --- END OF FINAL FIX ---

    @Column(name = "expected_credits", nullable = false)
    private BigDecimal expectedCredits;

    @Column(name = "services", columnDefinition = "TEXT")
    private String services;

    @Column(name = "use_case", columnDefinition = "TEXT")
    private String useCase;

    @Column(name = "status", nullable = false)
    private String status = "submitted";

    @Column(name = "submitted_date")
    @Temporal(TemporalType.TIMESTAMP)
    private Date submittedDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // Default constructor
    public CreditRequest() {}

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

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
}