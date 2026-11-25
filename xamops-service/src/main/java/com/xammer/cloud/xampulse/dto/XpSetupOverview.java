package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpSetupOverview(XpAccountState state, boolean demo, String homeRegion, XpBucketRef bucket,
        List<XpSetupRegion> regions, XpSetupInstances instances, int coveragePct, Instant lastDataAt,
        XpArchiveStatus archive) {

    public XpSetupOverview(XpAccountState state, boolean demo, String homeRegion, XpBucketRef bucket,
            List<XpSetupRegion> regions, XpSetupInstances instances, int coveragePct, Instant lastDataAt) {
        this(state, demo, homeRegion, bucket, regions, instances, coveragePct, lastDataAt, null);
    }

    /** Archive writer health. {@code state} is OFF, OK or FAILING; {@code lastError} is an exception class name only. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record XpArchiveStatus(String state, int failures, String lastError, Instant watermark) {}
}
