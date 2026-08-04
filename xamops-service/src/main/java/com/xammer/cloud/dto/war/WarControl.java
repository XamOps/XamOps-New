package com.xammer.cloud.dto.war;

import java.util.List;

/**
 * A single hardening control from the cloud security advisory, independent of any
 * customer account. Immutable reference data — see {@code WarControlCatalog}.
 *
 * @param id            stable identifier, e.g. {@code AWS-16}. Used as the check-result key.
 * @param ordinal       the row number from the source advisory, for stable display ordering.
 * @param category      top-level family, e.g. {@code Network Security}.
 * @param subCategory   family sub-group, e.g. {@code Security Groups &amp; Access}.
 * @param title         the control statement.
 * @param description   implementation steps.
 * @param severity      advisory severity: Critical / High / Medium / Low.
 * @param tools         AWS/GCP/Azure services that implement the control.
 * @param reference     vendor documentation URL.
 * @param controlType   advisory classification: Recommendation / Guidelines / Disclaimer.
 * @param businessImpact what it costs the customer when the control is missing.
 * @param impactLevel   severity of that business impact.
 * @param attackPaths   how an attacker monetises the gap.
 * @param automated     true when WAR evaluates this control against live cloud state.
 */
public record WarControl(
        String id,
        int ordinal,
        String category,
        String subCategory,
        String title,
        String description,
        String severity,
        String tools,
        String reference,
        String controlType,
        String businessImpact,
        String impactLevel,
        List<WarAttackPath> attackPaths,
        boolean automated) {
}
