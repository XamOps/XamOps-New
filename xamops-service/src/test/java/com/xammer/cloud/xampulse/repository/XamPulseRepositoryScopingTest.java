package com.xammer.cloud.xampulse.repository;

import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;
import org.springframework.data.repository.Repository;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every finder on a tenant entity (one with a cloudAccountId field) must take the tenant as a parameter, so a
 * repository method that reads across tenants cannot be added by accident.
 */
class XamPulseRepositoryScopingTest {

    private static final Set<String> SYSTEM_SCANS = Set.of("findAllEnabledForScheduler");
    private static final List<Class<?>> REPOS = List.of(XpAccountConfigRepository.class, XpAccountRegionRepository.class, XpAgentRepository.class,
            XpAgentCredentialRepository.class, XpLogSourceRepository.class, XpAlertRuleRepository.class, XpAlertStateRepository.class,
            XpIncidentRepository.class, XpIncidentEventRepository.class, XpSilenceRepository.class, XpChannelRepository.class,
            XpSavedViewRepository.class, XpUsageDailyRepository.class, XpAuditRepository.class, XpMigrationLogRepository.class);

    private static Class<?> entityOf(Class<?> repo) {
        return ResolvableType.forClass(repo).as(Repository.class).getGeneric(0).resolve();
    }

    private static boolean hasTenantField(Class<?> entity) {
        try {
            entity.getDeclaredField("cloudAccountId");
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    @Test
    void everyDeclaredFinderOnATenantEntityCarriesTheTenant() {
        int checked = 0;
        List<String> offenders = new ArrayList<>();
        for (Class<?> repo : REPOS) {
            Class<?> entity = entityOf(repo);
            assertTrue(entity.isAnnotationPresent(Entity.class), entity.getName());
            if (!hasTenantField(entity)) {
                continue;
            }
            for (Method m : repo.getDeclaredMethods()) {
                if (SYSTEM_SCANS.contains(m.getName())) {
                    continue;
                }
                checked++;
                boolean scoped = false;
                for (Parameter p : m.getParameters()) {
                    if (p.getName().startsWith("cloudAccountId")) {
                        scoped = true;
                    }
                }
                if (!scoped) {
                    offenders.add(repo.getSimpleName() + "." + m.getName());
                }
            }
        }
        assertTrue(offenders.isEmpty(), "finders without a tenant parameter: " + offenders);
        assertTrue(checked >= 25, "expected to inspect the finders, inspected " + checked);
    }

    @Test
    void theOnlySystemWideScanIsTheSchedulersEnabledRuleList() {
        List<String> found = new ArrayList<>();
        for (Class<?> repo : REPOS) {
            for (Method m : repo.getDeclaredMethods()) {
                if (SYSTEM_SCANS.contains(m.getName())) {
                    found.add(repo.getSimpleName() + "." + m.getName());
                }
            }
        }
        assertEquals(List.of("XpAlertRuleRepository.findAllEnabledForScheduler"), found);
    }

    @Test
    void entitiesWithoutATenantFieldAreExactlyTheParentJoinedOnes() {
        List<String> noTenant = new ArrayList<>();
        for (Class<?> repo : REPOS) {
            if (!hasTenantField(entityOf(repo))) {
                noTenant.add(entityOf(repo).getSimpleName());
            }
        }
        assertEquals(Set.of("XpAgentCredential", "XpAlertState", "XpIncidentEvent", "XpMigrationLog"), Set.copyOf(noTenant));
    }
}
