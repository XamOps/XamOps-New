package com.xammer.billops.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TeamUserDTO {
    private Long id;
    private String username;
    private String email;
    private String role;
    private String password; // Only used for create/update, never returned
}
