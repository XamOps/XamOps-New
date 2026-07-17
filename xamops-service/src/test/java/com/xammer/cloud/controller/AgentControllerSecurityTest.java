package com.xammer.cloud.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security unit test for AgentController.
 * Ensures all UI-facing endpoints have @PreAuthorize("isAuthenticated()") protection
 * to prevent unauthorized access and protect sensitive credentials/API keys.
 */
public class AgentControllerSecurityTest {

    @Test
    public void testUiEndpointsHavePreAuthorize() throws Exception {
        Class<AgentController> clazz = AgentController.class;

        // List of specific UI-facing methods we expect to be annotated
        List<String> expectedUiMethods = Arrays.asList(
                "getInstallInfo",
                "saveDbAgentConfig",
                "loadDbAgentConfig",
                "getDbAgentStatus",
                "getAgentStatus",
                "getAgentStatusByCluster",
                "getAgentEvents",
                "registerDbAgent",
                "deleteDbAgent"
        );

        for (Method method : clazz.getDeclaredMethods()) {
            // Check if it's one of our expected UI methods
            if (expectedUiMethods.contains(method.getName())) {
                // Ensure the method has the @PreAuthorize annotation
                PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
                assertNotNull(preAuthorize, "Method " + method.getName() + " must have @PreAuthorize annotation!");

                // Ensure the value is isAuthenticated()
                String value = preAuthorize.value();
                assertTrue(value.contains("isAuthenticated()"),
                        "Method " + method.getName() + " @PreAuthorize must contain 'isAuthenticated()', but was: " + value);
            }
        }
    }
}
