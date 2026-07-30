package com.xammer.cloud.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class K8sEksSecurityControllerTest {

    @Test
    public void testCloudK8sSecurityControllerHasPreAuthorize() {
        PreAuthorize annotation = CloudK8sSecurityController.class.getAnnotation(PreAuthorize.class);
        assertNotNull(annotation, "CloudK8sSecurityController should be annotated with @PreAuthorize");
        assertEquals("isAuthenticated()", annotation.value(), "PreAuthorize expression should be isAuthenticated()");
    }

    @Test
    public void testCloudK8sControllerHasPreAuthorize() {
        PreAuthorize annotation = CloudK8sController.class.getAnnotation(PreAuthorize.class);
        assertNotNull(annotation, "CloudK8sController should be annotated with @PreAuthorize");
        assertEquals("isAuthenticated()", annotation.value(), "PreAuthorize expression should be isAuthenticated()");
    }

    @Test
    public void testEksControllerHasPreAuthorize() {
        PreAuthorize annotation = EksController.class.getAnnotation(PreAuthorize.class);
        assertNotNull(annotation, "EksController should be annotated with @PreAuthorize");
        assertEquals("isAuthenticated()", annotation.value(), "PreAuthorize expression should be isAuthenticated()");
    }
}
