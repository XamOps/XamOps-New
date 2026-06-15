package com.xammer.cloud.dto.devops;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.SavedCustomization;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Response view of a {@link SavedCustomization}. Flattens the entity into a
 * serialization-friendly shape (no lazy JPA associations) and parses the
 * persisted JSON parameter values back into a map.
 *
 * Requirements: REQ-2.2.7 (Save &amp; Reuse Presets)
 */
public class SavedCustomizationResponse {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Long id;
    private String userId;
    private Long scriptId;
    private String customizationName;
    private Map<String, Object> parameterValues = new HashMap<>();
    private String generatedCode;
    private Boolean isDefault;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastUsedAt;

    public SavedCustomizationResponse() {}

    /** Build a response DTO from a persisted customization entity. */
    public static SavedCustomizationResponse from(SavedCustomization entity) {
        SavedCustomizationResponse r = new SavedCustomizationResponse();
        r.id = entity.getId();
        r.userId = entity.getUserId();
        r.scriptId = entity.getScript() != null ? entity.getScript().getId() : null;
        r.customizationName = entity.getCustomizationName();
        r.parameterValues = parseValues(entity.getParameterValues());
        r.generatedCode = entity.getGeneratedCode();
        r.isDefault = entity.getIsDefault();
        r.createdAt = entity.getCreatedAt();
        r.updatedAt = entity.getUpdatedAt();
        r.lastUsedAt = entity.getLastUsedAt();
        return r;
    }

    private static Map<String, Object> parseValues(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new HashMap<>();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            // Fall back to an empty map if the stored value is not valid JSON.
            return new HashMap<>();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public Long getScriptId() { return scriptId; }
    public void setScriptId(Long scriptId) { this.scriptId = scriptId; }

    public String getCustomizationName() { return customizationName; }
    public void setCustomizationName(String customizationName) { this.customizationName = customizationName; }

    public Map<String, Object> getParameterValues() { return parameterValues; }
    public void setParameterValues(Map<String, Object> parameterValues) {
        this.parameterValues = parameterValues != null ? parameterValues : new HashMap<>();
    }

    public String getGeneratedCode() { return generatedCode; }
    public void setGeneratedCode(String generatedCode) { this.generatedCode = generatedCode; }

    public Boolean getIsDefault() { return isDefault; }
    public void setIsDefault(Boolean isDefault) { this.isDefault = isDefault; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }
}
