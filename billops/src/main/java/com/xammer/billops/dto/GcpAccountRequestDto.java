package com.xammer.billops.dto;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class GcpAccountRequestDto {
    private String accountName;
    private String projectId; // Legacy field, kept for compatibility
    private String serviceAccountKey;
    
    // --- New Fields Required by GcpDataService ---
    private String gcpProjectId;
    private String billingExportTable;
    private String gcpWorkloadIdentityPoolId;
    private String gcpWorkloadIdentityProviderId;
}