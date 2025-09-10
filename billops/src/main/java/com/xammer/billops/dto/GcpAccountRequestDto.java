package com.xammer.billops.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GcpAccountRequestDto {
    private String accountName;
    private String projectId;
    private String serviceAccountKey;
}