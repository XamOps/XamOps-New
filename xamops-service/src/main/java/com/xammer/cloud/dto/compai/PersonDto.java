package com.xammer.cloud.dto.compai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PersonDto {
    private String id;
    private String name;         // user.name, falls back to email
    private String email;
    private String role;         // "owner" | "admin" | "member" ...
    private String department;
    private String jobTitle;
    private boolean active;
}
