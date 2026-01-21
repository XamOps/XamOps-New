package com.xammer.cloud.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TeamUserDTO {
    private Long id;
    private String username;
    private String email;
    private String role;
    private String password; // Only used for create/update, never returned
    private List<Long> accountIds; // Cloud account IDs this user can access
    private List<ProfileDTO.CloudAccountInfo> cloudAccounts; // Full cloud account details for display
}
