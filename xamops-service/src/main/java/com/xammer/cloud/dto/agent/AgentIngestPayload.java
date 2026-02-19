package com.xammer.cloud.dto.agent;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Payload structure for XamOps agent data push.
 * Agent sends this to POST /api/v1/agent/ingest
 */
public class AgentIngestPayload {

    /**
     * Schema version for forward compatibility
     */
    private String schemaVersion = "1.0";

    /**
     * Agent version
     */
    private String agentVersion;

    /**
     * Cluster name (required - identifies the cluster)
     */
    private String clusterName;

    /**
     * Cloud account ID (required - identifies the account)
     */
    private Long cloudAccountId;

    /**
     * Timestamp when data was collected
     */
    private LocalDateTime collectedAt;

    /**
     * List of data batches
     */
    private List<DataBatch> batches;

    // Getters and Setters
    public String getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(String schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public String getAgentVersion() {
        return agentVersion;
    }

    public void setAgentVersion(String agentVersion) {
        this.agentVersion = agentVersion;
    }

    public String getClusterName() {
        return clusterName;
    }

    public void setClusterName(String clusterName) {
        this.clusterName = clusterName;
    }

    public Long getCloudAccountId() {
        return cloudAccountId;
    }

    public void setCloudAccountId(Long cloudAccountId) {
        this.cloudAccountId = cloudAccountId;
    }

    public LocalDateTime getCollectedAt() {
        return collectedAt;
    }

    public void setCollectedAt(LocalDateTime collectedAt) {
        this.collectedAt = collectedAt;
    }

    public List<DataBatch> getBatches() {
        return batches;
    }

    public void setBatches(List<DataBatch> batches) {
        this.batches = batches;
    }

    /**
     * Inner class representing a batch of collected data
     */
    public static class DataBatch {
        /**
         * Collector type:
         * - trivy-vulnerabilities
         * - kubescape-compliance
         * - opencost-allocation
         * - cluster-workloads
         * - falco-events
         */
        private String collector;

        /**
         * Raw items from the collector (CRD objects, API responses, etc.)
         */
        private List<Object> items;

        /**
         * Number of items in this batch
         */
        private int count;

        /**
         * Timestamp when this batch was collected
         */
        private LocalDateTime collectedAt;

        // Getters and Setters
        public String getCollector() {
            return collector;
        }

        public void setCollector(String collector) {
            this.collector = collector;
        }

        public List<Object> getItems() {
            return items;
        }

        public void setItems(List<Object> items) {
            this.items = items;
        }

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }

        public LocalDateTime getCollectedAt() {
            return collectedAt;
        }

        public void setCollectedAt(LocalDateTime collectedAt) {
            this.collectedAt = collectedAt;
        }
    }
}
