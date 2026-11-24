package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.service.autospotting.AutoSpottingFeatureFlags;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Defense in depth for the AWS mutating programs (plan-phase-18.md task 18.3, design-core.md §6.3
 * switch (p)). The program is built only while <em>both</em> {@code autospotting.aws.v2-mutations-enabled}
 * and {@code autospotting.aws.conversions-enabled} are true; both stay false in every environment
 * until Phase 23. The confirm path refuses first (switch (p), {@code AS_PROVIDER_NOT_READY}); this gate
 * is the second lock, so a confirmed op that somehow reaches the provider still never builds a step
 * that can mutate, and {@link AwsMutatingClient} is never obtained.
 */
@Component
public class AwsProviderGate {

    private final AutoSpottingSwitchProperties switches;
    private final AutoSpottingFeatureFlags flags;

    public AwsProviderGate(AutoSpottingSwitchProperties switches, AutoSpottingFeatureFlags flags) {
        this.switches = switches;
        this.flags = flags;
    }

    /** True only when every AWS mutation switch is on. */
    public boolean open() {
        return switches.v2MutationsEnabled(true) && flags.isAwsConversionsEnabled();
    }

    /** @throws AutoSpottingApiException 409 {@code AS_PROVIDER_NOT_READY} while the gate is closed. */
    public void requireOpen() {
        if (!open()) {
            throw new AutoSpottingApiException(HttpStatus.CONFLICT, "AS_PROVIDER_NOT_READY",
                    "AutoSpotting is not ready to change AWS groups yet.");
        }
    }
}
