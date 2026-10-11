package com.xammer.cloud.dto.k8s;

import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/xamops/k8s/capabilities?accountId=} (design §11.4, schema version 1): what the
 * caller may change on each registered cluster of one AWS account, and why not.
 *
 * <p>
 * Every timestamp is an ISO-8601 UTC instant. {@code readiness} always lists all eight §6.7 keys in
 * property order, with {@code reason} and {@code message} only while a key is not ready.
 * {@code connection} and {@code writeGate} at the top level use the stricter of the entitled rows;
 * each cluster carries its own, from the row that owns its config. No internal ids are exposed.
 */
public record K8sCapabilitiesDto(
        int schemaVersion,
        String accountId,
        String generatedAt,
        Connection connection,
        UserCapability user,
        WriteGate writeGate,
        Map<String, Readiness> readiness,
        VerticalEngine verticalEngine,
        List<Cluster> clusters) {

    public static final int SCHEMA_VERSION = 1;

    /** @param accessLevel {@code READ_WRITE} or {@code READ_ONLY}; @param status the account status, e.g. {@code CONNECTED} */
    public record Connection(String accessLevel, String status) {
    }

    /** @param reasons {@code ["DEMO"]}, {@code ["NO_USER_PERMISSION"]} or empty; @param detail set with NO_USER_PERMISSION */
    public record UserCapability(boolean canChangeAutomation, List<String> reasons, String detail) {
    }

    /** @param reasons every failing write gate, in refusal order */
    public record WriteGate(boolean allowed, List<String> reasons) {
    }

    /** @param reason and message are null when ready */
    public record Readiness(boolean ready, String reason, String message) {
    }

    /** {@code xamops.automation.vertical.enabled}: when false the UI makes no engine call. */
    public record VerticalEngine(boolean enabled) {
    }

    /** @param status CONNECTED (data within 15 min), STALE, NOT_INSTALLED or UNKNOWN */
    public record XamopsAgent(String status, String lastDataAt, String version) {
    }

    /** @param status CONNECTED (seen within 5 min), STALE, NOT_INSTALLED or UNKNOWN */
    public record AutomationAgent(String status, String lastSeenAt, String version) {
    }

    /** @param derivedState NOT_INSTALLED, OFF, ANALYSIS or AUTOMATED (design D5) */
    public record NodeAutomation(boolean enabled, String mode, Boolean evictorAnalyseOnly, String derivedState) {
    }

    /** @param displayed whether the dashboard shows this source (kubescape, opencost and karpenter flags) */
    public record DataSource(String type, String collectedAt, Integer items, boolean displayed) {
    }

    public record Cluster(
            String clusterName,
            boolean registered,
            Connection connection,
            WriteGate writeGate,
            String dataSourceMode,
            XamopsAgent agent,
            AutomationAgent automationAgent,
            NodeAutomation nodeAutomation,
            List<DataSource> dataSources) {
    }
}
