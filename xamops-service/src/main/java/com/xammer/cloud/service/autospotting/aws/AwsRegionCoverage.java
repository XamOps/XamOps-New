package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.aws.events.AwsEventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Whether an account's AutoSpotting event pipeline covers a region (design-aws.md §9.4, PO-22, AC-EVT-04).
 * Reads {@code autospotting_region_coverage}, written by {@link AwsCoverageJob}: only {@code COVERED}
 * passes. {@code MISSING}, {@code SKIPPED}, {@code UNKNOWN} (the check failed) and an absent row all block,
 * so the answer fails closed. A conversion in an uncovered region would not see its Spot interruptions.
 */
@Component
public class AwsRegionCoverage {

    private static final Logger log = LoggerFactory.getLogger(AwsRegionCoverage.class);
    public static final String COVERED = "COVERED";

    private final AwsEventStore store;

    @Autowired
    public AwsRegionCoverage(AwsEventStore store) {
        this.store = store;
    }

    /** No store: nothing is covered. For tests that stub {@link #covered} or want the fail-closed answer. */
    public AwsRegionCoverage() {
        this.store = null;
    }

    /** True when {@code region} of {@code account} is verified covered. */
    public boolean covered(CloudAccount account, String region) {
        if (store == null || account == null || account.getId() == null || region == null) {
            return false;
        }
        try {
            return store.coverageStatus(account.getId(), region).map(COVERED::equals).orElse(false);
        } catch (RuntimeException e) {
            log.warn("region coverage unreadable for account {}: {}", account.getId(), e.toString());
            return false; // fail closed
        }
    }
}
