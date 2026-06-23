package com.xammer.cloud.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    @SuppressWarnings("unchecked")
    void globalExceptionHandler_sanitizesErrorMessage() {
        // Arrange
        Exception sensitiveException = new RuntimeException("Sensitive database error with password=secret123");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/test");

        // Act
        ResponseEntity<?> response = handler.globalExceptionHandler(sensitiveException, request);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("message")).isEqualTo("An unexpected error occurred. Please contact support.");
        assertThat(body.get("message").toString()).doesNotContain("Sensitive");
        assertThat(body.get("message").toString()).doesNotContain("secret123");
    }
}
