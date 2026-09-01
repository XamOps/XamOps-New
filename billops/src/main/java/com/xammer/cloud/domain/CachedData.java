package com.xammer.cloud.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import java.time.LocalDateTime;

@Entity
public class CachedData {

    @Id
    private String cacheKey;

    // No @Lob: the column is TEXT, and on PostgreSQL @Lob makes Hibernate treat it as a
    // large object -- read through a LOB stream, which fails outside a transaction with
    // "Unable to access lob stream" / "Large Objects may not be used in auto-commit mode".
    // Hibernate 5 tolerated the mismatch; Hibernate 7 does not. columnDefinition = "TEXT"
    // already gives unbounded storage. Same fix as ScriptVersion/DevOpsScript/JenkinsIntegrationConfig.
    @Column(columnDefinition = "TEXT")
    private String jsonData;

    private LocalDateTime lastUpdated;

    public CachedData() {
    }

    public CachedData(String cacheKey, String jsonData, LocalDateTime lastUpdated) {
        this.cacheKey = cacheKey;
        this.jsonData = jsonData;
        this.lastUpdated = lastUpdated;
    }

    public String getCacheKey() { return cacheKey; }
    public void setCacheKey(String cacheKey) { this.cacheKey = cacheKey; }

    public String getJsonData() { return jsonData; }
    public void setJsonData(String jsonData) { this.jsonData = jsonData; }

    public LocalDateTime getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(LocalDateTime lastUpdated) { this.lastUpdated = lastUpdated; }
}
