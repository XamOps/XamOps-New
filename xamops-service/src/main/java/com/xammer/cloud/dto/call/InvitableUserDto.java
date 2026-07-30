package com.xammer.cloud.dto.call;

import com.xammer.cloud.domain.User;

/**
 * A user the current caller is allowed to pull into a live call.
 *
 * The eligible set is scoped by role server-side (a customer sees only their own
 * client's users; an admin sees the ticket client's users plus the BillOps admin
 * pool), so this DTO carries just what the picker needs to render and select.
 */
public class InvitableUserDto {

    private Long userId;
    private String name;
    private String email;
    /** "ADMIN" for BillOps admins, "CLIENT_USER" otherwise — drives the picker grouping. */
    private String kind;

    public InvitableUserDto() {
    }

    public InvitableUserDto(Long userId, String name, String email, String kind) {
        this.userId = userId;
        this.name = name;
        this.email = email;
        this.kind = kind;
    }

    public static InvitableUserDto from(User u) {
        boolean admin = u.getRole() != null && u.getRole().toUpperCase().contains("BILLOPS_ADMIN");
        return new InvitableUserDto(u.getId(), u.getUsername(), u.getEmail(),
                admin ? "ADMIN" : "CLIENT_USER");
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }
}
