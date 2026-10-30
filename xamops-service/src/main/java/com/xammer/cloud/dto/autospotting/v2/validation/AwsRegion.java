package com.xammer.cloud.dto.autospotting.v2.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.ReportAsSingleViolation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * An AWS region: {@code ^[a-z]{2}(-[a-z]+)+-\d$} (design-core.md §11 "Validation"). A composed
 * {@link Pattern} constraint so the one regex lives in one place for every v2 DTO (AC-SEC-02).
 * A null value passes; combine with {@code @NotNull} where the field is required.
 */
@Documented
@Constraint(validatedBy = {})
@Pattern(regexp = "^[a-z]{2}(-[a-z]+)+-\\d$", message = "must be a valid AWS region")
@ReportAsSingleViolation
@Target({FIELD, PARAMETER, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface AwsRegion {
    String message() default "must be a valid AWS region";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
