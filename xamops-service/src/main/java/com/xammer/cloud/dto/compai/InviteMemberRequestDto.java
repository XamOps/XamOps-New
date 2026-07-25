package com.xammer.cloud.dto.compai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request body for inviting a single new member. role must be a comp.ai built-in role name (e.g. "admin", "employee"). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InviteMemberRequestDto {
    private String email;
    private String role;
}
