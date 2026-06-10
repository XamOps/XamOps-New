package com.xammer.cloud.dto.devops;

import javax.validation.constraints.NotNull;
import java.util.HashMap;
import java.util.Map;

/**
 * Request payload for generating a customized script: a map of parameter name
 * to the user-supplied value. Values are validated against each parameter's
 * constraints by the service before generation.
 *
 * Requirements: REQ-2.2.2 (Dynamic Form Generation), REQ-2.2.4 (Real-time Validation)
 */
public class CustomizeScriptRequest {

    @NotNull(message = "inputs are required")
    private Map<String, Object> inputs = new HashMap<>();

    public CustomizeScriptRequest() {}

    public Map<String, Object> getInputs() { return inputs; }
    public void setInputs(Map<String, Object> inputs) {
        this.inputs = inputs != null ? inputs : new HashMap<>();
    }
}
