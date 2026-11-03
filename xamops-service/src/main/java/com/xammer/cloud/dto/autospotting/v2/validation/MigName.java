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
 * A GCP managed-instance-group (MIG) name: {@code ^[a-z]([-a-z0-9]{0,61}[a-z0-9])?$} (design-core.md
 * §11 "Validation", GCP resource naming). A null value passes; combine with {@code @NotNull} where
 * required.
 */
@Documented
@Constraint(validatedBy = {})
@Pattern(regexp = "^[a-z]([-a-z0-9]{0,61}[a-z0-9])?$", message = "must be a valid instance group name")
@ReportAsSingleViolation
@Target({FIELD, PARAMETER, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface MigName {
    String message() default "must be a valid instance group name";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
