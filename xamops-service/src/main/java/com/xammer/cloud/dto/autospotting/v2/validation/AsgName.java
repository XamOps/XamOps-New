package com.xammer.cloud.dto.autospotting.v2.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * An Auto Scaling group name: 1 to 255 characters with no control characters (design-core.md §11
 * "Validation"). The AWS API accepts nearly any Unicode in an ASG name, so a strict regex would
 * reject real groups; this constraint only bounds the length and bans control characters.
 * Validated by {@link AsgNameValidator}. A null value passes; combine with {@code @NotNull} where
 * required.
 */
@Documented
@Constraint(validatedBy = AsgNameValidator.class)
@Target({FIELD, PARAMETER, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface AsgName {
    String message() default "must be a valid Auto Scaling group name";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
