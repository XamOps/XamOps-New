package com.xammer.cloud.dto.autospotting.v2.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validator for {@link AsgName} (design-core.md §11): 1 to 255 characters, no ISO control
 * characters. A null value passes (use {@code @NotNull} to require presence).
 */
public class AsgNameValidator implements ConstraintValidator<AsgName, String> {

    private static final int MAX_LENGTH = 255;

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        if (value.isEmpty() || value.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
