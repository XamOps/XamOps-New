package com.xammer.billops.dto;

/**
 * A client row for the BillOps admin User Manager.
 *
 * <p>The page builds its client groups by grouping the user list by client name, so a client
 * with no users produces no group and is invisible there — it cannot be given its first user,
 * and it cannot be removed. This DTO is the list that fixes that: every client the caller may
 * see, whether or not anyone belongs to it.
 *
 * <p>{@code userCount} and {@code cloudAccountCount} are the two counts the delete guard checks
 * (see {@code AppUserService.deleteClient}), sent along so the UI can disable the delete action
 * and explain why instead of offering a click that can only come back as a 409.
 */
public class AdminClientDTO {

    private Long id;
    private String name;
    /** Billing / signup email on the client record; null for admin-created clients. */
    private String email;
    private long userCount;
    private long cloudAccountCount;

    public AdminClientDTO() {
    }

    public AdminClientDTO(Long id, String name, String email, long userCount, long cloudAccountCount) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.userCount = userCount;
        this.cloudAccountCount = cloudAccountCount;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public long getUserCount() { return userCount; }
    public void setUserCount(long userCount) { this.userCount = userCount; }
    public long getCloudAccountCount() { return cloudAccountCount; }
    public void setCloudAccountCount(long cloudAccountCount) { this.cloudAccountCount = cloudAccountCount; }
}
