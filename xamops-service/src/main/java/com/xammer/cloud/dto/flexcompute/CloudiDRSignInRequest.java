package com.xammer.cloud.dto.flexcompute;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CloudiDRSignInRequest {
    private String email;
    private String password;
}
