package com.xammer.cloud.dto.agent;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Response sent back to the agent after data ingestion.
 */
public class AgentIngestResponse {

    private boolean success;
    private String message;
    private LocalDateTime receivedAt;
    private Map<String, BatchResult> results = new HashMap<>();

    // Constructors
    public AgentIngestResponse() {
        this.receivedAt = LocalDateTime.now();
    }

    public AgentIngestResponse(boolean success, String message) {
        this();
        this.success = success;
        this.message = message;
    }

    // Static factory methods
    public static AgentIngestResponse success() {
        return new AgentIngestResponse(true, "Data received successfully");
    }

    public static AgentIngestResponse error(String message) {
        return new AgentIngestResponse(false, message);
    }

    // Getters and Setters
    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(LocalDateTime receivedAt) {
        this.receivedAt = receivedAt;
    }

    public Map<String, BatchResult> getResults() {
        return results;
    }

    public void setResults(Map<String, BatchResult> results) {
        this.results = results;
    }

    public void addResult(String collector, BatchResult result) {
        this.results.put(collector, result);
    }

    /**
     * Result for each batch processed
     */
    public static class BatchResult {
        private boolean success;
        private int itemsProcessed;
        private String error;

        public BatchResult() {
        }

        public BatchResult(boolean success, int itemsProcessed) {
            this.success = success;
            this.itemsProcessed = itemsProcessed;
        }

        public BatchResult(String error) {
            this.success = false;
            this.error = error;
        }

        public static BatchResult success(int itemsProcessed) {
            return new BatchResult(true, itemsProcessed);
        }

        public static BatchResult error(String error) {
            return new BatchResult(error);
        }

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public int getItemsProcessed() {
            return itemsProcessed;
        }

        public void setItemsProcessed(int itemsProcessed) {
            this.itemsProcessed = itemsProcessed;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }
    }
}
