package com.xammer.cloud.dto.devops;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.HashMap;
import java.util.Map;

/**
 * Request payload for saving a reusable customization preset for a script.
 *
 * Requirements: REQ-2.2.7 (Save &amp; Reuse Presets), REQ-2.8.2 (Input Validation)
 */
public class SaveCustomizationRequest {

    @NotNull(message = "scriptId is required")
    private Long scriptId;

    @NotBlank(message = "customizationName is required")
    @Size(max = 200, message = "customizationName must not exceed 200 characters")
    private String customizationName;

    @NotNull(message = "inputs are required")
    private Map<String, Object> inputs = new HashMap<>();

    /** When true, this preset becomes the user's default for the script. */
    private boolean isDefault = false;

    public SaveCustomizationRequest() {}

    public Long getScriptId() { return scriptId; }
    public void setScriptId(Long scriptId) { this.scriptId = scriptId; }

    public String getCustomizationName() { return customizationName; }
    public void setCustomizationName(String customizationName) { this.customizationName = customizationName; }

    public Map<String, Object> getInputs() { return inputs; }
    public void setInputs(Map<String, Object> inputs) {
        this.inputs = inputs != null ? inputs : new HashMap<>();
    }

    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }
}
