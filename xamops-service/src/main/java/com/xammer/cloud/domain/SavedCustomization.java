package com.xammer.cloud.domain;

import javax.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "saved_customizations", indexes = {
    @Index(name = "idx_user_customizations", columnList = "user_id, script_id")
})
@EntityListeners(AuditingEntityListener.class)
public class SavedCustomization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "script_id", nullable = false)
    private DevOpsScript script;

    @Column(name = "customization_name", nullable = false, length = 200)
    private String customizationName; // User-defined name for this preset

    @Column(name = "parameter_values", columnDefinition = "TEXT", nullable = false)
    private String parameterValues; // JSON map of parameter_name -> value

    @Lob
    @Column(name = "generated_code", columnDefinition = "TEXT")
    private String generatedCode; // The customized script code

    @Column(name = "is_default")
    private Boolean isDefault = false; // User's default preset for this script

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    // Constructors
    public SavedCustomization() {}

    public SavedCustomization(String userId, DevOpsScript script, String customizationName, String parameterValues, String generatedCode) {
        this.userId = userId;
        this.script = script;
        this.customizationName = customizationName;
        this.parameterValues = parameterValues;
        this.generatedCode = generatedCode;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public DevOpsScript getScript() { return script; }
    public void setScript(DevOpsScript script) { this.script = script; }

    public String getCustomizationName() { return customizationName; }
    public void setCustomizationName(String customizationName) { this.customizationName = customizationName; }

    public String getParameterValues() { return parameterValues; }
    public void setParameterValues(String parameterValues) { this.parameterValues = parameterValues; }

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
