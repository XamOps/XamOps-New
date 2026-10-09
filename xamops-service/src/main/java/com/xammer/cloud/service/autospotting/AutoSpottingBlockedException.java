package com.xammer.cloud.service.autospotting;

import org.springframework.http.HttpStatus;

/**
 * An AutoSpotting action that XamOps refused on purpose, before touching the cloud.
 *
 * <p>
 * Each instance carries the HTTP status and a stable {@code code} the frontend can switch
 * on. The AutoSpotting controllers map it to {@code {success:false, code, error}} with a
 * controller-local {@code @ExceptionHandler}, which wins over the catch-all in
 * {@code GlobalExceptionHandler}.
 */
public class AutoSpottingBlockedException extends RuntimeException {

    public static final String CONVERSIONS_PAUSED = "AUTOSPOTTING_CONVERSIONS_PAUSED";
    public static final String READ_ONLY_CONNECTION = "AUTOSPOTTING_READ_ONLY_CONNECTION";
    public static final String CONNECTION_NOT_VERIFIED = "AUTOSPOTTING_CONNECTION_NOT_VERIFIED";
    public static final String GKE_MANAGED = "AUTOSPOTTING_GKE_MANAGED";
    public static final String GCP_MIG_UPDATE_CONFLICT = "GCP_MIG_UPDATE_CONFLICT";

    private final HttpStatus status;
    private final String code;

    public AutoSpottingBlockedException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static AutoSpottingBlockedException conversionsPaused() {
        return new AutoSpottingBlockedException(HttpStatus.CONFLICT, CONVERSIONS_PAUSED,
                "New Spot conversions are paused while AutoSpotting is being upgraded. "
                        + "Groups already on Spot can still be switched back to On-Demand.");
    }

    public static AutoSpottingBlockedException readOnlyConnection() {
        return new AutoSpottingBlockedException(HttpStatus.CONFLICT, READ_ONLY_CONNECTION,
                "This AWS connection is read-only. "
                        + "Reconnect it with read-write access to change Auto Scaling groups.");
    }

    public static AutoSpottingBlockedException connectionNotVerified() {
        return new AutoSpottingBlockedException(HttpStatus.CONFLICT, CONNECTION_NOT_VERIFIED,
                "This AWS connection isn't verified yet. "
                        + "Finish connecting the account before changing Auto Scaling groups.");
    }

    public static AutoSpottingBlockedException gkeManaged() {
        return new AutoSpottingBlockedException(HttpStatus.UNPROCESSABLE_ENTITY, GKE_MANAGED,
                "This instance group belongs to a GKE node pool, so GKE manages its VMs. "
                        + "To use Spot, create a Spot node pool in GKE.");
    }

    public static AutoSpottingBlockedException gcpUpdateConflict(String migName) {
        return new AutoSpottingBlockedException(HttpStatus.CONFLICT, GCP_MIG_UPDATE_CONFLICT,
                "Another update is already running on " + migName
                        + ", or it changed while XamOps was updating it. The instance group wasn't changed. "
                        + "Try again after the current rollout finishes.");
    }
}
