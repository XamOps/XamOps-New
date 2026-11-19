package com.xammer.cloud.service.gcp;

/** A GCP project that is already registered; the controller answers 409 (AC-SPN-14). */
public class GcpProjectAlreadyConnectedException extends RuntimeException {
    public GcpProjectAlreadyConnectedException(String projectId) {
        super("GCP project '" + projectId + "' is already connected.");
    }
}
