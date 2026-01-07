package com.xammer.cloud.dto.cloudidr;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseResponse {
    private Boolean success;
    private String message;
    private String cidrId;
    private String status;
    private String purchaseId;
    private Long purchasedAt;
}
