package com.xammer.cloud.dto.cloudidr;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloudIdrSearchRequest {

    @NotBlank(message = "Account ID is required")
    private String accountId;

    @NotBlank(message = "Instance type is required")
    private String instanceType;

    @NotBlank(message = "Platform is required")
    private String platform;

    @NotNull(message = "Quantity is required")
    private Integer quantity;

    @NotBlank(message = "Start date is required")
    private String startDate;

    @NotBlank(message = "End date is required")
    private String endDate;

    private String startTime;
    private String endTime;

    // This field should already exist
    private String region;
}
