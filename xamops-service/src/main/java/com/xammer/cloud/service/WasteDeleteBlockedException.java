package com.xammer.cloud.service;

import org.springframework.http.HttpStatus;

/**
 * A waste delete refused because AutoSpotting manages the Auto Scaling Group (design-core.md section 7.6,
 * FR-CMP-02). {@code code} is {@code AUTOSPOTTING_MANAGED_GROUP} (acknowledge to proceed) or
 * {@code AS_GROUP_BUSY} (APPLYING or REVERTING: refused even with the acknowledgment); {@code state} is the
 * group's state. Nothing was deleted and no AWS call was made when this is thrown.
 */
public class WasteDeleteBlockedException extends RuntimeException {

    public static final String MANAGED_GROUP = "AUTOSPOTTING_MANAGED_GROUP";
    public static final String GROUP_BUSY = "AS_GROUP_BUSY";

    private final String code;
    private final String state;

    public WasteDeleteBlockedException(String code, String state, String message) {
        super(message);
        this.code = code;
        this.state = state;
    }

    public String getCode() {
        return code;
    }

    public String getState() {
        return state;
    }

    public HttpStatus getStatus() {
        return HttpStatus.CONFLICT;
    }
}
