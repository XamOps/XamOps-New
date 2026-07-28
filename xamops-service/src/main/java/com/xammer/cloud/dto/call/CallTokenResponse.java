package com.xammer.cloud.dto.call;

import lombok.AllArgsConstructor;
import lombok.Data;

/** Everything a browser needs to attach to the LiveKit room for a call. */
@Data
@AllArgsConstructor
public class CallTokenResponse {
    private String livekitUrl;
    private String token;
    private String roomName;
    private String identity;
    private Long callId;
}
