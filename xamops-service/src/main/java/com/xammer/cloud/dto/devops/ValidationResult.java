package com.xammer.cloud.dto.devops;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of validating user-supplied parameter values against a script's
 * parameter constraints (required flag, regex pattern, min/max range, options).
 *
 * Requirements: REQ-2.2.4 (Real-time Validation)
 */
public class ValidationResult {

    private boolean valid;
    private List<ParameterValidationError> errors = new ArrayList<>();

    public ValidationResult() {}

    public ValidationResult(boolean valid, List<ParameterValidationError> errors) {
        this.valid = valid;
        this.errors = errors != null ? errors : new ArrayList<>();
    }

    /** Convenience factory for a successful validation with no errors. */
    public static ValidationResult valid() {
        return new ValidationResult(true, new ArrayList<>());
    }

    public void addError(String parameterName, String errorMessage) {
        this.errors.add(new ParameterValidationError(parameterName, errorMessage));
        this.valid = false;
    }

    public boolean isValid() { return valid; }
    public void setValid(boolean valid) { this.valid = valid; }

    public List<ParameterValidationError> getErrors() { return errors; }
    public void setErrors(List<ParameterValidationError> errors) {
        this.errors = errors != null ? errors : new ArrayList<>();
    }
}
