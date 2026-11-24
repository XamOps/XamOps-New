package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.domain.CloudAccount;

/**
 * Where the AWS provider gets a read-only, per-region view of a customer account. The production
 * implementation is {@link AwsAutoSpottingClientFactory}, which assumes the stored read role at
 * runtime; tests supply a view built from mocked SDK clients, so no test calls AWS.
 */
@FunctionalInterface
public interface AwsViewSource {

    /** A read-only view of {@code account} in {@code region}. */
    AwsReadOnlyView view(CloudAccount account, String region);
}
