package com.xammer.cloud.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "script_parameters", indexes = {
    @Index(name = "idx_script_params", columnList = "script_id, display_order")
})
public class ScriptParameter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "script_id", nullable = false)
    private DevOpsScript script;

    @Column(nullable = false, length = 100)
    private String name; // Parameter identifier (e.g., "REGION", "INSTANCE_TYPE")

    @Column(nullable = false, length = 200)
    private String label; // User-friendly label

    @Column(columnDefinition = "TEXT")
    private String description; // Help text for the parameter

    @Column(name = "parameter_type", nullable = false, length = 50)
    private String parameterType; // text, number, select, multiselect, boolean, region, instanceType

    @Column(nullable = false)
    private Boolean required = true;

    @Column(name = "default_value", columnDefinition = "TEXT")
    private String defaultValue;

    @Column(name = "validation_rules", columnDefinition = "TEXT")
    private String validationRules; // JSON format: {pattern, min, max, options:[]}

    @Column(length = 50)
    private String category; // infrastructure, configuration, security, networking

    @Column(name = "depends_on", length = 100)
    private String dependsOn; // Show only if another parameter has specific value

    @Column(name = "prefill_from", length = 50)
    private String prefillFrom; // userProfile, cloudAccount, previousUsage

    @Column(name = "display_order")
    private Integer displayOrder = 0;

    // Constructors
    public ScriptParameter() {}

    public ScriptParameter(DevOpsScript script, String name, String label, String parameterType, Boolean required) {
        this.script = script;
        this.name = name;
        this.label = label;
        this.parameterType = parameterType;
        this.required = required;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public DevOpsScript getScript() { return script; }
    public void setScript(DevOpsScript script) { this.script = script; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getParameterType() { return parameterType; }
    public void setParameterType(String parameterType) { this.parameterType = parameterType; }

    public Boolean getRequired() { return required; }
    public void setRequired(Boolean required) { this.required = required; }

    public String getDefaultValue() { return defaultValue; }
    public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }

    public String getValidationRules() { return validationRules; }
    public void setValidationRules(String validationRules) { this.validationRules = validationRules; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getDependsOn() { return dependsOn; }
    public void setDependsOn(String dependsOn) { this.dependsOn = dependsOn; }

    public String getPrefillFrom() { return prefillFrom; }
    public void setPrefillFrom(String prefillFrom) { this.prefillFrom = prefillFrom; }

    public Integer getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }
}
