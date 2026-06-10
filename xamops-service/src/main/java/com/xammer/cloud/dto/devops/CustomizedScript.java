package com.xammer.cloud.dto.devops;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * A script whose {{PARAMETER_NAME}} placeholders have been replaced with the
 * user's supplied values, ready to be copied to the clipboard or downloaded.
 *
 * Requirements: REQ-2.2.1, REQ-2.2.2 (Customization Engine)
 */
public class CustomizedScript {

    private Long scriptId;
    private String scriptName;
    private String customizedCode;
    private Map<String, Object> parameterValues = new HashMap<>();
    private LocalDateTime generatedAt;

    public CustomizedScript() {}

    public CustomizedScript(Long scriptId, String scriptName, String customizedCode,
                            Map<String, Object> parameterValues, LocalDateTime generatedAt) {
        this.scriptId = scriptId;
        this.scriptName = scriptName;
        this.customizedCode = customizedCode;
        this.parameterValues = parameterValues != null ? parameterValues : new HashMap<>();
        this.generatedAt = generatedAt;
    }

    public Long getScriptId() { return scriptId; }
    public void setScriptId(Long scriptId) { this.scriptId = scriptId; }

    public String getScriptName() { return scriptName; }
    public void setScriptName(String scriptName) { this.scriptName = scriptName; }

    public String getCustomizedCode() { return customizedCode; }
    public void setCustomizedCode(String customizedCode) { this.customizedCode = customizedCode; }

    public Map<String, Object> getParameterValues() { return parameterValues; }
    public void setParameterValues(Map<String, Object> parameterValues) {
        this.parameterValues = parameterValues != null ? parameterValues : new HashMap<>();
    }

    public LocalDateTime getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(LocalDateTime generatedAt) { this.generatedAt = generatedAt; }
}
