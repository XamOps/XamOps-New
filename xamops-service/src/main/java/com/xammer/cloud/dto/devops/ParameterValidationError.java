package com.xammer.cloud.dto.devops;

/**
 * Represents a single validation failure for a script customization parameter.
 *
 * Requirements: REQ-2.2.4 (Real-time Validation)
 */
public class ParameterValidationError {

    private String parameterName;
    private String errorMessage;

    public ParameterValidationError() {}

    public ParameterValidationError(String parameterName, String errorMessage) {
        this.parameterName = parameterName;
        this.errorMessage = errorMessage;
    }

    public String getParameterName() { return parameterName; }
    public void setParameterName(String parameterName) { this.parameterName = parameterName; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
