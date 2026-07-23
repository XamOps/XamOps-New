package com.xammer.cloud.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import static org.assertj.core.api.Assertions.assertThat;

class S3BrowserControllerSecurityTest {

    @Test
    void testControllerHasPreAuthorizeIsAuthenticated() {
        // Assert that the controller class has @PreAuthorize("isAuthenticated()")
        PreAuthorize annotation = S3BrowserController.class.getAnnotation(PreAuthorize.class);
        assertThat(annotation)
                .withFailMessage("S3BrowserController must be annotated with @PreAuthorize")
                .isNotNull();

        assertThat(annotation.value())
                .withFailMessage("S3BrowserController @PreAuthorize annotation must specify 'isAuthenticated()'")
                .isEqualTo("isAuthenticated()");
    }
}
