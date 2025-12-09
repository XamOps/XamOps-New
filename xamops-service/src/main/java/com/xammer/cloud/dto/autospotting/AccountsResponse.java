package com.xammer.cloud.dto.autospotting;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccountsResponse {
    private String mode; // "single" or "cross-account"
    private List<AccountInfo> accounts;
}
