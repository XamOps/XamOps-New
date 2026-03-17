package com.xammer.cloud.dto.dbops;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DbSecurityCheckDto {
    private String instanceId;
    private String region;
    private String checkName;
    private String severity;  // HIGH, MEDIUM, INFO
    private String status;    // PASS, FAIL, WARN
    private String details;
}
