package com.xammer.cloud.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AlertErrorDto {
    private String service;
    private String errorType;
    private String message;
    private boolean partial;
    private String timestamp;
}
