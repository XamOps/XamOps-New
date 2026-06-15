package com.xammer.cloud.dto.devops;

import com.xammer.cloud.domain.ScriptParameter;

import java.util.ArrayList;
import java.util.List;

/**
 * The full set of customizable parameters for a script, used to drive dynamic
 * form generation on the frontend.
 *
 * Requirements: REQ-2.2.1 (Parameter Definition), REQ-2.2.2 (Dynamic Form Generation)
 */
public class ScriptParameters {

    private Long scriptId;
    private String scriptName;
    private List<ScriptParameter> parameters = new ArrayList<>();

    public ScriptParameters() {}

    public ScriptParameters(Long scriptId, String scriptName, List<ScriptParameter> parameters) {
        this.scriptId = scriptId;
        this.scriptName = scriptName;
        this.parameters = parameters != null ? parameters : new ArrayList<>();
    }

    public Long getScriptId() { return scriptId; }
    public void setScriptId(Long scriptId) { this.scriptId = scriptId; }

    public String getScriptName() { return scriptName; }
    public void setScriptName(String scriptName) { this.scriptName = scriptName; }

    public List<ScriptParameter> getParameters() { return parameters; }
    public void setParameters(List<ScriptParameter> parameters) {
        this.parameters = parameters != null ? parameters : new ArrayList<>();
    }
}
