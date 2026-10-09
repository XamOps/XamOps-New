package com.xammer.cloud.domain;

import com.xammer.cloud.domain.type.LegacyLobTextType;
import com.xammer.cloud.domain.type.OidShapeGuard;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Parameter;
import org.hibernate.annotations.Type;
import java.time.LocalDateTime;

@Entity
@DynamicUpdate
public class CachedData {

    @Id
    private String cacheKey;

    // No @Lob: the column is TEXT, and on PostgreSQL @Lob makes Hibernate treat it as a
    // large object -- read through a LOB stream, which fails outside a transaction with
    // "Unable to access lob stream" / "Large Objects may not be used in auto-commit mode".
    // Hibernate 5 tolerated the mismatch; Hibernate 7 does not. columnDefinition = "TEXT"
    // already gives unbounded storage. Same fix as ScriptVersion/DevOpsScript/JenkinsIntegrationConfig.
    @Type(value = LegacyLobTextType.class, parameters = @Parameter(name = LegacyLobTextType.KEY_COLUMN, value = "cache_key"))
    @Column(columnDefinition = "TEXT")
    private String jsonData;

    private LocalDateTime lastUpdated;

    // No-argument constructor for JPA
    public CachedData() {
    }

    // Refuses an OID-shaped inline value in both write modes (OidShapeGuard).
    @PrePersist
    @PreUpdate
    private void rejectOidShapedJsonData() {
        OidShapeGuard.checkEntityWrite("cached_data", cacheKey, jsonData);
    }

    // All-args constructor
    public CachedData(String cacheKey, String jsonData, LocalDateTime lastUpdated) {
        this.cacheKey = cacheKey;
        this.jsonData = jsonData;
        this.lastUpdated = lastUpdated;
    }

    public String getCacheKey() {
        return cacheKey;
    }

    public void setCacheKey(String cacheKey) {
        this.cacheKey = cacheKey;
    }

    public String getJsonData() {
        return jsonData;
    }

    public void setJsonData(String jsonData) {
        this.jsonData = jsonData;
    }

    public LocalDateTime getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(LocalDateTime lastUpdated) {
        this.lastUpdated = lastUpdated;
    }
}