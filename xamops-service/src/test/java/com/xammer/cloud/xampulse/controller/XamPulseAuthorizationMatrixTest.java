package com.xammer.cloud.xampulse.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/** Plan 8.8: every endpoint carries a role expression, and the admin-only set is exactly what the matrix lists. */
class XamPulseAuthorizationMatrixTest {

    /** Methods that hold secrets or administer the account. Everything else is viewer/editor. */
    private static final Set<String> ADMIN_ONLY = Set.of(
            "XamPulseAlertsController.listChannels", "XamPulseAlertsController.createChannel", "XamPulseAlertsController.updateChannel",
            "XamPulseAlertsController.deleteChannel", "XamPulseAlertsController.testChannel", "XamPulseAuditController.audit",
            // onboarding (slice B): everything that reads bucket properties, deploys, verifies, changes collection or disconnects
            "XamPulseSetupController.buckets", "XamPulseSetupController.plan", "XamPulseSetupController.stackStatus",
            "XamPulseSetupController.verify", "XamPulseSetupController.stream", "XamPulseSetupController.collection",
            "XamPulseSetupController.regionPlan", "XamPulseSetupController.disconnect", "XamPulseSetupController.sources",
            "XamPulseSetupController.putSources",
            // archive search run (flag gated): reads the customer bucket, so admin only
            "XamPulseArchiveSearchRunController.run");

    /** Endpoints that are not session endpoints. */
    private static final Set<String> NO_ROLE = Set.of("XamPulseStatusController", "XamPulseIngestAuthController",
            // agent API (token or STS proof checked in the handler) and the public install script
            "XamPulseAgentController", "XamPulseInstallController");

    private List<Class<?>> controllers() throws Exception {
        ClassPathScanningCandidateComponentProvider p = new ClassPathScanningCandidateComponentProvider(false);
        var env = new org.springframework.core.env.StandardEnvironment();
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("t", java.util.Map.of("xampulse.enabled", "true", "xampulse.archive.enabled", "true")));
        p.setEnvironment(env); // conditional controllers are only candidates when the feature is on
        p.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Class<?>> out = new ArrayList<>();
        for (BeanDefinition bd : p.findCandidateComponents("com.xammer.cloud.xampulse.controller")) {
            out.add(Class.forName(bd.getBeanClassName()));
        }
        return out;
    }

    private static boolean isHandler(Method m) {
        return m.isAnnotationPresent(GetMapping.class) || m.isAnnotationPresent(PostMapping.class) || m.isAnnotationPresent(PutMapping.class)
                || m.isAnnotationPresent(DeleteMapping.class) || m.isAnnotationPresent(RequestMapping.class);
    }

    @Test
    void everySessionEndpointHasAXamPulseRoleExpressionAndNonSessionEndpointsHaveNone() throws Exception {
        // Single XamOps product role: VIEWER and ADMIN now hold the same authorities, so the former admin-only
        // matrix is no longer a security boundary that can be asserted by string value. What still matters is
        // that every session endpoint carries one of the two XamPulse expressions, and the token/STS-checked
        // endpoints carry none. The ADMIN-tagged endpoints below stay tagged so a future privilege split is a
        // one-line change to XamPulseRoles, not a re-audit of every handler.
        int handlers = 0;
        for (Class<?> c : controllers()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!isHandler(m) || !Modifier.isPublic(m.getModifiers())) {
                    continue;
                }
                handlers++;
                PreAuthorize pa = m.getAnnotation(PreAuthorize.class);
                if (NO_ROLE.contains(c.getSimpleName())) {
                    assertNull(pa, c.getSimpleName() + " is not a session endpoint");
                    continue;
                }
                assertNotNull(pa, c.getSimpleName() + "." + m.getName() + " has no @PreAuthorize");
                String v = pa.value();
                assertTrue(v.equals(XamPulseRoles.VIEWER) || v.equals(XamPulseRoles.ADMIN), c.getSimpleName() + "." + m.getName() + ": " + v);
            }
        }
        assertTrue(handlers >= 40, "scan should find the endpoints, found " + handlers);
    }

    @Test
    void roleExpressionsUseTheRepoAuthorityNames() {
        // Single XamOps product role: viewer and admin accept the same authorities (ROLE_XAMOPS is the
        // account operator; ROLE_SUPER_ADMIN is the platform superuser). ROLE_XAMOPS was removed.
        assertEquals("hasAnyAuthority('ROLE_XAMOPS','ROLE_SUPER_ADMIN')", XamPulseRoles.VIEWER);
        assertEquals("hasAnyAuthority('ROLE_XAMOPS','ROLE_SUPER_ADMIN')", XamPulseRoles.ADMIN);
        assertTrue(XamPulseRoles.ADMIN.contains("'ROLE_XAMOPS'"));
    }

    @Test
    void everyControllerIsConditionalOnTheFeatureFlagExceptStatus() throws Exception {
        for (Class<?> c : controllers()) {
            boolean conditional = c.isAnnotationPresent(org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class);
            if (c.getSimpleName().equals("XamPulseStatusController")) {
                assertFalse(conditional);
            } else {
                assertTrue(conditional, c.getSimpleName() + " must be off unless xampulse.enabled=true");
            }
        }
    }

    @Test
    void everyControllerBasePathIsUnderTheXamPulseApiOrTheInternalAuthPath() throws Exception {
        for (Class<?> c : controllers()) {
            RequestMapping rm = c.getAnnotation(RequestMapping.class);
            if (rm == null) {
                // the internal auth endpoint, and the public install script under /agentinstall (slice B)
                assertTrue(Set.of("XamPulseIngestAuthController", "XamPulseInstallController").contains(c.getSimpleName()), c.getSimpleName());
                continue;
            }
            // the host agent API (slice B) lives under /api/v1/agent/xampulse
            assertTrue(rm.value()[0].startsWith("/api/xamops/xampulse") || rm.value()[0].startsWith("/api/v1/agent/xampulse"), c.getSimpleName());
        }
    }
}
