package com.xammer.cloud.controller.compai;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security test for CompAiController.
 * Ensures the controller is hardened with class-level @PreAuthorize("isAuthenticated()") annotation.
 */
class CompAiControllerSecurityTest {

    @Test
    void controllerClass_shouldBeSecuredWithPreAuthorizeIsAuthenticated() {
        PreAuthorize annotation = CompAiController.class.getAnnotation(PreAuthorize.class);
        assertThat(annotation)
                .as("CompAiController class must be annotated with @PreAuthorize")
                .isNotNull();
        assertThat(annotation.value())
                .as("@PreAuthorize value must be 'isAuthenticated()'")
                .isEqualTo("isAuthenticated()");
    }
}
