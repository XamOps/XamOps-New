package com.xammer.cloud.controller;

import com.xammer.cloud.security.CloudAccountAccessService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA depth-testing: cross-tenant (IDOR) guard, structural check.
 *
 * <p>Reports 02-bugs-B (B-01..B-04) and 02-bugs-C (C-01..C-04) found controllers that take an
 * {@code accountId} from the URL and never consult {@link CloudAccountAccessService}. This test
 * is a cheap tripwire: a controller that handles tenant account ids must depend on the access
 * service. It does not prove every handler calls it; the MockMvc tenant-A-vs-tenant-B tests
 * recommended in 99-qa-summary.md do that. Bug-exposing cases are {@code @Disabled}.
 */
class TenantIsolationStructureQaTest {

    private static boolean dependsOnAccessService(String className) throws Exception {
        Class<?> c = Class.forName(className);
        for (Field f : c.getDeclaredFields()) {
            if (CloudAccountAccessService.class.isAssignableFrom(f.getType())) return true;
        }
        for (Constructor<?> k : c.getDeclaredConstructors()) {
            if (Arrays.stream(k.getParameterTypes()).anyMatch(CloudAccountAccessService.class::isAssignableFrom)) {
                return true;
            }
        }
        return false;
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "com.xammer.cloud.controller.CurInsightsController",
            "com.xammer.cloud.controller.ComplianceOpsController"})
    @DisplayName("characterization: these controllers already enforce tenant access")
    void guardedControllers(String cls) throws Exception {
        assertThat(dependsOnAccessService(cls)).as(cls).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "com.xammer.cloud.controller.EbsDiskRightsizingController",
            "com.xammer.cloud.controller.aws.AwsRightsizingController",
            "com.xammer.cloud.controller.XamOpsRightsizingController",
            "com.xammer.cloud.controller.CostController",
            "com.xammer.cloud.controller.gcp.GcpRightsizingController",
            "com.xammer.cloud.controller.gcp.GcpCostInsightsController"})
    @Disabled("BUG B-01/B-02/B-03/B-04: cost + rightsizing controllers (incl. write paths) have no tenant ownership check")
    @DisplayName("cost and rightsizing controllers must resolve accounts through CloudAccountAccessService")
    void costAndRightsizingControllersAreTenantScoped(String cls) throws Exception {
        assertThat(dependsOnAccessService(cls)).as(cls).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "com.xammer.cloud.controller.SecurityController",
            "com.xammer.cloud.controller.WarController",
            "com.xammer.cloud.controller.CloudmapController",
            "com.xammer.cloud.controller.CloudlistController",
            "com.xammer.cloud.controller.InventoryPlaneController"})
    @Disabled("BUG C-01/C-02/C-03/C-04: security, WAR, CloudMap, CloudList and inventory endpoints have no tenant ownership check")
    @DisplayName("security/WAR/inventory controllers must resolve accounts through CloudAccountAccessService")
    void securityAndInventoryControllersAreTenantScoped(String cls) throws Exception {
        assertThat(dependsOnAccessService(cls)).as(cls).isTrue();
    }
}
