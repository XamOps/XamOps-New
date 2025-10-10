package com.xammer.cloud.dto.gcp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GcpCommittedUseDiscountDto {
    private String name;
    private String type; // e.g., "compute-engine-dollar-based"
    private String status;
    private String region;
    private String term; // e.g., "1-year"
    private BigDecimal commitmentAmount;
    private String commitmentUnit;
    private String id;
    private String description;
    private String plan;
}