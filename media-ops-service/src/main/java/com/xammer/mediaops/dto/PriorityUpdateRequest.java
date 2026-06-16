package com.xammer.mediaops.dto;

import lombok.Data;

@Data
public class PriorityUpdateRequest {
    private String priority; // "HIGH", "NORMAL", "LOW"
}
