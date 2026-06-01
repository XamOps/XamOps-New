package com.xammer.cloud.dto.gcp;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class CspBillingConfigDto {
    private Long id;
    private String name;
    private String gcpProjectId;
    private String datasetId;
    private String tableId;
    private String serviceAccountKey; // null in list responses; required for create/update
}
