package com.xammer.billops.service.upload;

import org.springframework.http.HttpStatus;

/** An upload the draft cannot be built from. The message is written to be shown as-is. */
public class UploadedBillException extends RuntimeException {

    private final HttpStatus status;

    public UploadedBillException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
