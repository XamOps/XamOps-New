package com.xammer.cloud.service.autospotting.gcp;

import com.xammer.cloud.domain.CloudAccount;

/**
 * Where the GCP provider gets a read-only view of a customer project. The production implementation
 * is {@link GcpViewFactory}; tests supply a view over an in-memory fake, so no test calls GCP.
 */
@FunctionalInterface
public interface GcpViewSource {
    /** A read-only view of the project {@code account} is connected to. */
    GcpReadOnlyView view(CloudAccount account);
}
