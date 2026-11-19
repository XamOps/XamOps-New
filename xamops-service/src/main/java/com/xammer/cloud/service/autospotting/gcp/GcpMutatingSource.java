package com.xammer.cloud.service.autospotting.gcp;

import com.xammer.cloud.domain.CloudAccount;

/**
 * Where the GCP conversion steps get the write-mode client. The production implementation is
 * {@link GcpMigClientMutatingSource}; tests supply a stub, so no test calls GCP. A step reaches this only
 * through {@link GcpStepServices#mutating}, which re-checks {@link GcpProviderGate} first.
 */
@FunctionalInterface
public interface GcpMutatingSource {
    GcpMigClient.Mutating mutating(CloudAccount account);
}
