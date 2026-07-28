package com.xammer.cloud.dto.call;

import lombok.Data;

@Data
public class InitiateCallRequest {
    private Long ticketId;
    /** AUDIO or VIDEO; defaults to AUDIO when absent. */
    private String mediaKind;
}
