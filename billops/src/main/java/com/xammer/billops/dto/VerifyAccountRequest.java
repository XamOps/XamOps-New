package com.xammer.billops.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class VerifyAccountRequest {
    private Long accountId;
    private String awsAccountId;
    private String roleName;
    private String externalId;
}