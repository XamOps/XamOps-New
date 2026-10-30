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
 * A GCP zone: a region followed by a {@code -<letter>} zone suffix, {@code ^[a-z]+-[a-z]+\d-[a-z]$}
 * (e.g. {@code us-central1-a}), per the GCP zone naming scheme (design-core.md §11 "Validation").
 * A null value passes; combine with {@code @NotNull} where required.
 */
@Documented
@Constraint(validatedBy = {})
@Pattern(regexp = "^[a-z]+-[a-z]+\\d-[a-z]$", message = "must be a valid GCP zone")
@ReportAsSingleViolation
@Target({FIELD, PARAMETER, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface GcpZone {
    String message() default "must be a valid GCP zone";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
