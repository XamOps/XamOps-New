package com.xammer.cloud.dto.billops;

import java.util.List;

/** One user on a {@link BillOpsClientDetailsDto} row. */
public class BillOpsUserDto {
    private Long id;
    private String username;
    private String email;
    private List<Long> accountAccessIds;
    private Long parentId;  // null = primary user, set = sub-user of that parent
    private java.time.LocalDateTime lastLoginAt;
    /**
     * Whether this user receives security and WAR email. Defaults TRUE, matching the column
     * default, so a client list cached before this field existed renders every user as subscribed
     * rather than as muted — the direction that cannot make an admin think delivery is off when it
     * is on.
     */
    private boolean emailNotificationsEnabled = true;

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public List<Long> getAccountAccessIds() {
        return accountAccessIds;
    }

    public void setAccountAccessIds(List<Long> accountAccessIds) {
        this.accountAccessIds = accountAccessIds;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public java.time.LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(java.time.LocalDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public boolean isEmailNotificationsEnabled() {
        return emailNotificationsEnabled;
    }

    public void setEmailNotificationsEnabled(boolean emailNotificationsEnabled) {
        this.emailNotificationsEnabled = emailNotificationsEnabled;
    }
}
