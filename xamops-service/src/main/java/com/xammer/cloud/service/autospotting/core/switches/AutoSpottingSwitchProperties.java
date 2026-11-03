package com.xammer.cloud.service.autospotting.core.switches;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The property-backed half of the kill-switch hierarchy (design-core.md §6.1, plan-phase-05.md task
 * 5.1). Same construction style as
 * {@link com.xammer.cloud.service.autospotting.AutoSpottingFeatureFlags} and
 * {@link com.xammer.cloud.service.autospotting.core.lease.LeaseProperties}: a single
 * {@code @Value}-bound constructor with a {@code :} placeholder default on every field, so an absent
 * property, an empty property and a missing profile all read the safe code default.
 *
 * <p>These are the deploy-config switches of core §6.1 rows (b), (c'), (p), (L) and (R). Row (a)
 * ({@code autospotting.enabled}) is read by {@link SwitchService} directly (it is the one flag set
 * true only in {@code application-prod.properties}; code default false, AC-SPN-11), and row (c)
 * ({@code autospotting.{aws,gcp}.conversions-enabled}) is read through
 * {@link com.xammer.cloud.service.autospotting.AutoSpottingFeatureFlags}. The effective value of any
 * switch is {@code property AND NOT db.engaged}: the DB can only tighten (FR-SPN-09).
 *
 * <p>Defaults (all safe, none declared in any profile):
 * <ul>
 *   <li>{@code autospotting.{aws,gcp}.enabled} = true (per-cloud master, row b)</li>
 *   <li>{@code autospotting.{aws,gcp}.conversions-allowlist-only} = true (row c')</li>
 *   <li>{@code autospotting.{aws,gcp}.v2-mutations-enabled} = false (row p)</li>
 *   <li>{@code autospotting.ledger.{aws,gcp}.writer-enabled} = false (row L)</li>
 *   <li>{@code autospotting.ledger.billing-enabled} = false (row L)</li>
 *   <li>{@code autospotting.mutations.dry-run} = false (row R)</li>
 * </ul>
 */
@Component
public class AutoSpottingSwitchProperties {

    private static final Logger log = LoggerFactory.getLogger(AutoSpottingSwitchProperties.class);

    private final boolean awsEnabled;
    private final boolean gcpEnabled;
    private final boolean awsConversionsAllowlistOnly;
    private final boolean gcpConversionsAllowlistOnly;
    private final boolean awsV2MutationsEnabled;
    private final boolean gcpV2MutationsEnabled;
    private final boolean ledgerAwsWriterEnabled;
    private final boolean ledgerGcpWriterEnabled;
    private final boolean ledgerBillingEnabled;
    private final boolean mutationsDryRun;

    public AutoSpottingSwitchProperties(
            @Value("${autospotting.aws.enabled:true}") boolean awsEnabled,
            @Value("${autospotting.gcp.enabled:true}") boolean gcpEnabled,
            @Value("${autospotting.aws.conversions-allowlist-only:true}") boolean awsConversionsAllowlistOnly,
            @Value("${autospotting.gcp.conversions-allowlist-only:true}") boolean gcpConversionsAllowlistOnly,
            @Value("${autospotting.aws.v2-mutations-enabled:false}") boolean awsV2MutationsEnabled,
            @Value("${autospotting.gcp.v2-mutations-enabled:false}") boolean gcpV2MutationsEnabled,
            @Value("${autospotting.ledger.aws.writer-enabled:false}") boolean ledgerAwsWriterEnabled,
            @Value("${autospotting.ledger.gcp.writer-enabled:false}") boolean ledgerGcpWriterEnabled,
            @Value("${autospotting.ledger.billing-enabled:false}") boolean ledgerBillingEnabled,
            @Value("${autospotting.mutations.dry-run:false}") boolean mutationsDryRun) {
        this.awsEnabled = awsEnabled;
        this.gcpEnabled = gcpEnabled;
        this.awsConversionsAllowlistOnly = awsConversionsAllowlistOnly;
        this.gcpConversionsAllowlistOnly = gcpConversionsAllowlistOnly;
        this.awsV2MutationsEnabled = awsV2MutationsEnabled;
        this.gcpV2MutationsEnabled = gcpV2MutationsEnabled;
        this.ledgerAwsWriterEnabled = ledgerAwsWriterEnabled;
        this.ledgerGcpWriterEnabled = ledgerGcpWriterEnabled;
        this.ledgerBillingEnabled = ledgerBillingEnabled;
        this.mutationsDryRun = mutationsDryRun;
        log.info("AutoSpotting switch properties: aws.enabled={} gcp.enabled={} "
                        + "aws.conversions-allowlist-only={} gcp.conversions-allowlist-only={} "
                        + "aws.v2-mutations-enabled={} gcp.v2-mutations-enabled={} "
                        + "ledger.aws.writer-enabled={} ledger.gcp.writer-enabled={} ledger.billing-enabled={} "
                        + "mutations.dry-run={}",
                awsEnabled, gcpEnabled, awsConversionsAllowlistOnly, gcpConversionsAllowlistOnly,
                awsV2MutationsEnabled, gcpV2MutationsEnabled, ledgerAwsWriterEnabled, ledgerGcpWriterEnabled,
                ledgerBillingEnabled, mutationsDryRun);
    }

    /** Per-cloud master (row b): {@code autospotting.{aws,gcp}.enabled}. */
    public boolean cloudEnabled(boolean aws) {
        return aws ? awsEnabled : gcpEnabled;
    }

    /** Lift allowlist gate (row c'): {@code autospotting.{aws,gcp}.conversions-allowlist-only}. */
    public boolean conversionsAllowlistOnly(boolean aws) {
        return aws ? awsConversionsAllowlistOnly : gcpConversionsAllowlistOnly;
    }

    /** Provider programs ready (row p): {@code autospotting.{aws,gcp}.v2-mutations-enabled}. */
    public boolean v2MutationsEnabled(boolean aws) {
        return aws ? awsV2MutationsEnabled : gcpV2MutationsEnabled;
    }

    /** Ledger writer (row L): {@code autospotting.ledger.{aws,gcp}.writer-enabled}. */
    public boolean ledgerWriterEnabled(boolean aws) {
        return aws ? ledgerAwsWriterEnabled : ledgerGcpWriterEnabled;
    }

    /** Ledger billing (row L): {@code autospotting.ledger.billing-enabled}. */
    public boolean ledgerBillingEnabled() {
        return ledgerBillingEnabled;
    }

    /** Dry-run (row R): {@code autospotting.mutations.dry-run}. */
    public boolean mutationsDryRun() {
        return mutationsDryRun;
    }
}
