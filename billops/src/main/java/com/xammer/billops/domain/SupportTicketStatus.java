package com.xammer.billops.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "support_ticket_status")
public class SupportTicketStatus {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false)
    private String status = "OPEN"; // OPEN, IN_PROGRESS, CLOSED

    @Column(name = "assigned_admin_username")
    private String assignedAdminUsername;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by_admin")
    private String updatedByAdmin;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getAssignedAdminUsername() { return assignedAdminUsername; }
    public void setAssignedAdminUsername(String assignedAdminUsername) { this.assignedAdminUsername = assignedAdminUsername; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public String getUpdatedByAdmin() { return updatedByAdmin; }
    public void setUpdatedByAdmin(String updatedByAdmin) { this.updatedByAdmin = updatedByAdmin; }
}
