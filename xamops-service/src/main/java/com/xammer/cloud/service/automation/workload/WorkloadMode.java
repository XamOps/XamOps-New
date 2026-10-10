package com.xammer.cloud.service.automation.workload;

/** Per-workload policy mode. */
public enum WorkloadMode {

    /** Not evaluated. The default for every workload, because a new feature should do nothing. */
    OFF,

    /** Evaluate and show recommendations; never act. */
    RECOMMEND,

    /**
     * Evaluate and apply automatically.
     *
     * <p><strong>Storable but inert in this run.</strong> It exists because the UI's policy drawer
     * needs the enum value. With no executor implemented and readiness false, every decision carries
     * {@code autoApplyEligible: false} — so a stored {@code AUTO} is inert by construction rather
     * than by a flag check that could be mis-evaluated.
     */
    AUTO
}
