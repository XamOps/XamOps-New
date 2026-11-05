package com.xammer.cloud.dto.autospotting.v2.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Pattern;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * A GCP region: {@code ^[a-z]+-[a-z]+\d$} (e.g. {@code us-central1}, {@code asia-south1}), per the
 * GCP region naming scheme (design-core.md §11 "Validation", "GCP zone and region patterns"). A
 * null value passes; combine with {@code @NotNull} where required.
 */
@Documented
@Constraint(validatedBy = {})
@Pattern(regexp = "^[a-z]+-[a-z]+\\d$", message = "must be a valid GCP region")
@ReportAsSingleViolation
@Target({FIELD, PARAMETER, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface GcpRegion {
    String message() default "must be a valid GCP region";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
