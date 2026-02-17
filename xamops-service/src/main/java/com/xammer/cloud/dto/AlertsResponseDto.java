package com.xammer.cloud.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AlertsResponseDto {
    private List<AlertDto> alerts;
    private List<AlertErrorDto> errors;
    private AlertsMetadataDto metadata;
}
