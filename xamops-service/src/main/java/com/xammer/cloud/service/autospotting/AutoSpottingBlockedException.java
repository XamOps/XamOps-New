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
    /** The Phase A disable endpoints refuse a v2-managed group: a plan is required (A-02, AC-PLN-07). */
    public static final String PLAN_REQUIRED = "PLAN_REQUIRED";
    /** The group lease is held, so the Phase A disable can't take it for the call (§7.4, §3.3). */
    public static final String AS_GROUP_BUSY = "AS_GROUP_BUSY";

    private final HttpStatus status;
    private final String code;
    /** An optional UI hint (e.g. the {@code planUrl} for {@link #PLAN_REQUIRED}); null when none. */
    private final String planUrl;

    public AutoSpottingBlockedException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public AutoSpottingBlockedException(HttpStatus status, String code, String message, String planUrl) {
        super(message);
        this.status = status;
        this.code = code;
        this.planUrl = planUrl;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    /** The {@code planUrl} hint for a {@link #PLAN_REQUIRED} refusal, or null. */
    public String getPlanUrl() {
        return planUrl;
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

    /**
     * 409 {@code PLAN_REQUIRED}: the Phase A disable endpoint refused a group whose {@code spot_group}
     * row is in a v2 state, or whose cloud's {@code v2-mutations-enabled} is true (A-02, AC-PLN-07).
     * The {@code planUrl} points the UI at the v2 Revert flow.
     */
    public static AutoSpottingBlockedException planRequired(String planUrl) {
        return new AutoSpottingBlockedException(HttpStatus.CONFLICT, PLAN_REQUIRED,
                "This group is now managed by AutoSpotting. Use the AutoSpotting Revert flow to switch it "
                        + "back to On-Demand.", planUrl);
    }

    /**
     * 409 {@code AS_GROUP_BUSY}: the Phase A disable couldn't take the group lease because a v2
     * operation holds it (§7.4, §3.3). Nothing was changed.
     */
    public static AutoSpottingBlockedException groupBusy() {
        return new AutoSpottingBlockedException(HttpStatus.CONFLICT, AS_GROUP_BUSY,
                "AutoSpotting is changing this group right now. Try again when it finishes.");
    }
}
