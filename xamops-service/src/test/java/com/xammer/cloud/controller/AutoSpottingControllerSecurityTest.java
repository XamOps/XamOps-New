package com.xammer.cloud.controller;

import com.xammer.cloud.controller.azure.AzureAutoSpottingController;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class AutoSpottingControllerSecurityTest {

    @Test
    public void testAutoSpottingControllerHasPreAuthorize() {
        PreAuthorize annotation = AutoSpottingController.class.getAnnotation(PreAuthorize.class);
        assertNotNull(annotation, "AutoSpottingController should be annotated with @PreAuthorize");
        assertEquals("isAuthenticated()", annotation.value(), "PreAuthorize expression should be isAuthenticated()");
    }

    @Test
    public void testGcpAutoSpottingControllerHasPreAuthorize() {
        PreAuthorize annotation = GcpAutoSpottingController.class.getAnnotation(PreAuthorize.class);
        assertNotNull(annotation, "GcpAutoSpottingController should be annotated with @PreAuthorize");
        assertEquals("isAuthenticated()", annotation.value(), "PreAuthorize expression should be isAuthenticated()");
    }

    @Test
    public void testAzureAutoSpottingControllerHasPreAuthorize() {
        PreAuthorize annotation = AzureAutoSpottingController.class.getAnnotation(PreAuthorize.class);
        assertNotNull(annotation, "AzureAutoSpottingController should be annotated with @PreAuthorize");
        assertEquals("isAuthenticated()", annotation.value(), "PreAuthorize expression should be isAuthenticated()");
    }
}
