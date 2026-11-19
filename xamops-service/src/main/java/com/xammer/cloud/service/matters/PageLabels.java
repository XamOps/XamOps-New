package com.xammer.cloud.service.matters;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Code-to-wording maps of the Dashboard, Remediation, Assists and Scan Management pages (design 5.7).
 *
 * <p>An unknown code falls back to {@link DspmLabels#prettify} ("SOME_CODE" → "Some Code"); null stays null.
 * The remediation action and status codes and wording are the console bundle's ({@code constants-a63ffc8b.js}
 * and {@code remediation-caa4d6cd.js}, read 2026-11-19), the triggers {@code activityCells-07e03be7.js}'s.
 */
public final class PageLabels {

    private static final Map<String, String> PLATFORMS = Map.of(
            "cloud", "Cloud",
            "on_prem", "On-Prem",
            "endpoint", "Endpoint");

    private static final Map<String, String> COVERAGES = Map.of(
            "SELECTED_DATASTORES", "Selected datastores",
            "ALL_DATASTORES", "All datastores");

    private static final Map<String, String> TRIGGERS = Map.of(
            "IMMEDIATE", "Immediate",
            "SCHEDULED", "Scheduled");

    private static final Map<String, String> RUN_STATUSES = Map.of(
            "SUCCESS", "Success",
            "FAILED", "Failed",
            "IN_PROGRESS", "Progress",
            "PAUSED", "Paused",
            "NOT_STARTED", "Not started");

    private static final Map<String, String> RUN_PHASES = Map.of(
            "FILE_DISCOVERY", "File Discovery",
            "CLASSIFICATION", "Classification",
            "COMPLETED", "Completed");

    private static final Map<String, String> REMEDIATION_STATUSES = Map.of(
            "PENDING", "Pending",
            "WAITING_APPROVAL", "Awaiting Approval",
            "SCHEDULED", "Scheduled",
            "IN_PROGRESS", "In Progress",
            "COMPLETED", "Completed",
            "NOOP", "No Change",
            "FAILED", "Failed",
            "REJECTED", "Rejected",
            "ROLLED_BACK", "Rolled Back",
            "CANCELLED", "Cancelled");

    private static final Map<String, String> REMEDIATION_ACTIONS = Map.of(
            "MAKE_PRIVATE", "Make Private",
            "REMOVE_EXTERNAL_ACCESS", "Remove External Access",
            "REMOVE_EXTERNAL_ACCESS_BY_TIME", "Remove External Access (timed)",
            "REVOKE_RECIPIENT", "Revoke Recipient",
            "DOWNGRADE_LINK_SCOPE", "Downgrade Link Scope",
            "SET_LINK_EXPIRY", "Set Link Expiry",
            "MOVE_TO_QUARANTINE", "Move to Quarantine",
            "DELETE_FILE", "Delete File",
            "DELETE_EMAIL", "Delete Email",
            "BLOCK_USER_QUERY", "Block User Query");

    private static final Map<String, String> REMEDIATION_TRIGGERS = Map.of(
            "MANUAL", "Manual",
            "POLICY", "Policy",
            "AI_AGENT", "AI Agent");

    private static final Map<String, String> SAAS_USER_STATUSES = Map.of(
            "ACTIVE", "Active",
            "SUSPENDED", "Suspended");

    private static final String GOOGLE_WORKSPACE = "GOOGLE_WORKSPACE";
    private static final String MICROSOFT_365 = "MICROSOFT_365";

    private static final Map<String, String> SAAS_PROVIDERS = Map.ofEntries(
            Map.entry("GMAIL", GOOGLE_WORKSPACE),
            Map.entry(GOOGLE_WORKSPACE, GOOGLE_WORKSPACE),
            Map.entry("GOOGLE_DRIVE", GOOGLE_WORKSPACE),
            Map.entry("SLACK", "SLACK"),
            Map.entry("ONE_DRIVE", MICROSOFT_365),
            Map.entry("SHAREPOINT", MICROSOFT_365),
            Map.entry("OUTLOOK", MICROSOFT_365),
            Map.entry("TEAMS", MICROSOFT_365),
            Map.entry(MICROSOFT_365, MICROSOFT_365),
            Map.entry("JIRA", "JIRA"),
            Map.entry("CONFLUENCE", "CONFLUENCE"),
            Map.entry("GITLAB", "GITLAB"));

    private static final Set<String> ACTIVE = Set.of("ACTIVE");

    private PageLabels() {
    }

    /** cloud → Cloud, on_prem → On-Prem, endpoint → Endpoint. */
    public static String platform(String code) {
        return lookup(PLATFORMS, code);
    }

    /** SELECTED_DATASTORES → Selected datastores, ALL_DATASTORES → All datastores. */
    public static String coverage(String code) {
        return lookup(COVERAGES, code);
    }

    /** IMMEDIATE → Immediate, SCHEDULED → Scheduled. */
    public static String trigger(String code) {
        return lookup(TRIGGERS, code);
    }

    /** A scan run's status: SUCCESS → Success, IN_PROGRESS → Progress, NOT_STARTED → Not started. */
    public static String runStatus(String code) {
        return lookup(RUN_STATUSES, code);
    }

    /** A scan run's phase: FILE_DISCOVERY → File Discovery, COMPLETED → Completed. */
    public static String runPhase(String code) {
        return lookup(RUN_PHASES, code);
    }

    /** WAITING_APPROVAL → Awaiting Approval, NOOP → No Change, and the rest of the console's statuses. */
    public static String remediationStatus(String code) {
        return lookup(REMEDIATION_STATUSES, code);
    }

    /** MAKE_PRIVATE → Make Private, REMOVE_EXTERNAL_ACCESS_BY_TIME → Remove External Access (timed). */
    public static String remediationAction(String code) {
        return lookup(REMEDIATION_ACTIONS, code);
    }

    /** MANUAL → Manual, POLICY → Policy, AI_AGENT → AI Agent. */
    public static String remediationTrigger(String code) {
        return lookup(REMEDIATION_TRIGGERS, code);
    }

    /** ACTIVE → Active; any other non-null code → Disabled. */
    public static String assistStatus(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return ACTIVE.contains(code.trim().toUpperCase(Locale.ROOT)) ? "Active" : "Disabled";
    }

    /** ACTIVE → Active, SUSPENDED → Suspended. */
    public static String saasUserStatus(String code) {
        return lookup(SAAS_USER_STATUSES, code);
    }

    /**
     * The console provider a SaaS row's subtype belongs to (design 4.6): GMAIL, GOOGLE_WORKSPACE and
     * GOOGLE_DRIVE → GOOGLE_WORKSPACE; SLACK; the Microsoft drives → MICROSOFT_365; JIRA; CONFLUENCE;
     * GITLAB; anything else → null (the integration is not listed).
     */
    public static String saasProvider(String subtype) {
        if (subtype == null || subtype.isBlank()) {
            return null;
        }
        return SAAS_PROVIDERS.get(subtype.trim().toUpperCase(Locale.ROOT));
    }

    private static String lookup(Map<String, String> labels, String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String label = labels.get(code.trim());
        return label != null ? label : DspmLabels.prettify(code);
    }
}
