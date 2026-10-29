package com.xammer.cloud.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Single owner of the DbOps feature flags (SEC-2/3).
 *
 * <p>
 * The Explain pipeline is disabled by default. Holding the default {@code false} literal in
 * exactly one place — here — keeps "is Explain on?" a single decision rather than three
 * {@code @Value} sites that could drift. When Explain is disabled the enqueue and result
 * endpoints answer 409, the agent command poll returns 204, and a late result write-back is
 * ignored, so the feature is fail-closed end to end.
 */
@Component
public class DbOpsFeatureFlags {

    private final boolean explainEnabled;

    public DbOpsFeatureFlags(
            @Value("${xamops.dbops.explain.enabled:false}") boolean explainEnabled) {
        this.explainEnabled = explainEnabled;
    }

    /** @return whether the DbOps Explain pipeline is enabled. Defaults to {@code false}. */
    public boolean isExplainEnabled() {
        return explainEnabled;
    }
}
