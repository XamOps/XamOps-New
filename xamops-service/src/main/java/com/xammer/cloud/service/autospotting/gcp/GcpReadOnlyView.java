package com.xammer.cloud.service.autospotting.gcp;

import com.xammer.cloud.service.autospotting.spi.ReadOnlyCloudView;

/**
 * GCP's read-only view of the cloud (design-gcp.md §1.3, core §7.2, §9.1): one project, and the
 * {@link GcpMigClient.ReadOnly} facade. The {@link GcpMigClient.Mutating} facade is not reachable
 * from here, so a mutating call cannot compile in planning (AC-PLN-01). Its accessors are
 * read-shaped; {@code AutoSpottingArchitectureTest} refuses a mutation-named method.
 */
public final class GcpReadOnlyView implements ReadOnlyCloudView {
    private final String project;
    private final GcpMigClient.ReadOnly reads;

    public GcpReadOnlyView(String project, GcpMigClient.ReadOnly reads) {
        this.project = project;
        this.reads = reads;
    }

    /** The GCP project this view reads. */
    public String project() {
        return project;
    }

    /** The read-only API calls. */
    public GcpMigClient.ReadOnly reads() {
        return reads;
    }
}
