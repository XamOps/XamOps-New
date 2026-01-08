package com.xammer.cloud.dto.cloudidr;

import lombok.Data;

@Data
public class CancelResponse {
    private String cidrId;
    private String status;
    private String message;
}
