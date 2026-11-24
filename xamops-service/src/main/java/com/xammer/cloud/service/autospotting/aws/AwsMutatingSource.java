package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.domain.CloudAccount;

/**
 * Where the AWS conversion steps get the write-mode client (design-aws.md §2.2). The production
 * implementation is {@link AwsAutoSpottingClientFactory}; tests supply a client built over a stubbed
 * SDK, so no test calls AWS. A step reaches this only after {@link AwsProviderGate} is open.
 */
@FunctionalInterface
public interface AwsMutatingSource {
    /** The {@code WRITE}-mode client for {@code account} in {@code region}. */
    AwsMutatingClient mutatingClient(CloudAccount account, String region);
}
