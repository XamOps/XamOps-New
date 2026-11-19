package com.xammer.cloud.service.autospotting.gcp;

import com.xammer.cloud.service.autospotting.AutoSpottingFeatureFlags;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Defense in depth for the GCP mutating programs (design-core.md §6.3 switch (p)). Both
 * {@code autospotting.gcp.v2-mutations-enabled} and {@code autospotting.gcp.conversions-enabled} stay
 * false in every environment; while either is false {@code executeConversion} and {@code restore}
 * refuse with {@code AS_PROVIDER_NOT_READY}. Even when both are on, no GCP program exists before
 * Phase 27, so the provider still refuses and {@link GcpMigClient.Mutating} is never obtained.
 */
@Component
public class GcpProviderGate {
    private final AutoSpottingSwitchProperties switches;
    private final AutoSpottingFeatureFlags flags;

    public GcpProviderGate(AutoSpottingSwitchProperties switches, AutoSpottingFeatureFlags flags) {
        this.switches = switches;
        this.flags = flags;
    }

    /** True only when every GCP mutation switch is on. */
    public boolean open() {
        return switches.v2MutationsEnabled(false) && flags.isGcpConversionsEnabled();
    }

    /** @throws AutoSpottingApiException 409 {@code AS_PROVIDER_NOT_READY}: the gate is closed or no program exists yet. */
    public void requireProgram() {
        throw new AutoSpottingApiException(HttpStatus.CONFLICT, "AS_PROVIDER_NOT_READY",
                "AutoSpotting is not ready to change GCP groups yet.");
    }
}
