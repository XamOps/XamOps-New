package com.xammer.cloud.service.autospotting.spi;

/**
 * A conversion profile passed to {@code checkEligibility} and carried by a {@link PlanRequest}
 * (design-core.md §9.1; profile values are PO-18, still OPEN — conservative preselected). The SPI
 * needs the profile only as an opaque, cloud-neutral handle: an {@code id} and the profile
 * parameters as JSON. The provider interprets {@code parametersJson}; the core never does.
 *
 * <p>Minimal by design: the full profile model (values, UI) arrives with plan creation in a later
 * phase (plan-phase-07+). This type exists so {@link SpotProvider} compiles as the cloud-neutral
 * boundary this phase defines.
 */
public record Profile(String id, String parametersJson) {

    public Profile {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("profile id is required");
        }
    }
}
