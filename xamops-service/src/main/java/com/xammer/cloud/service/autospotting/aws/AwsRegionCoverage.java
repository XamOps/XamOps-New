package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.domain.CloudAccount;
import org.springframework.stereotype.Component;

/**
 * Whether an account's AutoSpotting event pipeline covers a region (design-aws.md §9.4, PO-22).
 * The coverage table arrives with Phase 22, so until then <strong>no region is covered</strong> and
 * every group reports {@code REGION_NOT_COVERED}. That is the safe default: a conversion in an
 * uncovered region would not see its Spot interruptions. Phase 22 replaces this bean.
 */
@Component
public class AwsRegionCoverage {

    /** True when {@code region} of {@code account} is covered by the event pipeline. Always false until Phase 22. */
    public boolean covered(CloudAccount account, String region) {
        return false;
    }
}
