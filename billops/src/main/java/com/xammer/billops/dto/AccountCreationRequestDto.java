package com.xammer.billops.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AccountCreationRequestDto {
    private String accountName;
    private String accessType;
}