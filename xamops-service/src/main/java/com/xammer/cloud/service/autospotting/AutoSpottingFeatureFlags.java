package com.xammer.cloud.service.autospotting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Phase A safety switches for AutoSpotting. All default to {@code false}.
 *
 * <ul>
 *   <li>{@code autospotting.aws.conversions-enabled}: new AWS Spot conversions (enable,
 *       apply config, register). Disable always stays available.</li>
 *   <li>{@code autospotting.gcp.conversions-enabled}: the same for GCP MIGs.</li>
 *   <li>{@code autospotting.aws.savings-ledger.enabled}: whether AWS code paths write
 *       {@code daily_spot_savings}. Off until the savings figures are trustworthy.</li>
 * </ul>
 */
@Component
public class AutoSpottingFeatureFlags {

    private static final Logger log = LoggerFactory.getLogger(AutoSpottingFeatureFlags.class);

    private final boolean awsConversionsEnabled;
    private final boolean gcpConversionsEnabled;
    private final boolean awsSavingsLedgerEnabled;

    public AutoSpottingFeatureFlags(
            @Value("${autospotting.aws.conversions-enabled:false}") boolean awsConversionsEnabled,
            @Value("${autospotting.gcp.conversions-enabled:false}") boolean gcpConversionsEnabled,
            @Value("${autospotting.aws.savings-ledger.enabled:false}") boolean awsSavingsLedgerEnabled) {
        this.awsConversionsEnabled = awsConversionsEnabled;
        this.gcpConversionsEnabled = gcpConversionsEnabled;
        this.awsSavingsLedgerEnabled = awsSavingsLedgerEnabled;
        log.info("AutoSpotting flags: aws.conversions-enabled={} gcp.conversions-enabled={} "
                + "aws.savings-ledger.enabled={}", awsConversionsEnabled, gcpConversionsEnabled,
                awsSavingsLedgerEnabled);
    }

    public boolean isAwsConversionsEnabled() {
        return awsConversionsEnabled;
    }

    public boolean isGcpConversionsEnabled() {
        return gcpConversionsEnabled;
    }

    public boolean isAwsSavingsLedgerEnabled() {
        return awsSavingsLedgerEnabled;
    }

    /** @throws AutoSpottingBlockedException 409 AUTOSPOTTING_CONVERSIONS_PAUSED when AWS conversions are off. */
    public void requireAwsConversionsEnabled() {
        if (!awsConversionsEnabled) {
            throw AutoSpottingBlockedException.conversionsPaused();
        }
    }

    /** @throws AutoSpottingBlockedException 409 AUTOSPOTTING_CONVERSIONS_PAUSED when GCP conversions are off. */
    public void requireGcpConversionsEnabled() {
        if (!gcpConversionsEnabled) {
            throw AutoSpottingBlockedException.conversionsPaused();
        }
    }
}
