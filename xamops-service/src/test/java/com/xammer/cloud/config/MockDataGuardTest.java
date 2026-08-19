package com.xammer.cloud.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mock mode replaces every cost, pricing and reservation figure with generated data. If it
 * were ever enabled in a customer-facing environment the dashboard would show plausible
 * fiction, so startup must fail rather than proceed.
 */
class MockDataGuardTest {

    private MockDataGuard guard(boolean useMockData, String... activeProfiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(activeProfiles);
        return new MockDataGuard(env, useMockData);
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "production", "uat", "preprod", "sit"})
    @DisplayName("fails startup when mock data is enabled in a production-like profile")
    void rejectsMockDataInProductionProfiles(String profile) {
        MockDataGuard g = guard(true, profile);
        IllegalStateException e = assertThrows(IllegalStateException.class, g::verifyMockDataIsSafe);
        assertTrue(e.getMessage().contains("aws.costexplorer.mock"));
        assertTrue(e.getMessage().contains(profile));
    }

    @Test
    @DisplayName("is case-insensitive about profile names")
    void rejectsMockDataRegardlessOfCase() {
        assertThrows(IllegalStateException.class, () -> guard(true, "PROD").verifyMockDataIsSafe());
        assertThrows(IllegalStateException.class, () -> guard(true, "Production").verifyMockDataIsSafe());
    }

    @Test
    @DisplayName("catches a production profile listed alongside others")
    void rejectsMockDataWhenProductionIsOneOfSeveralProfiles() {
        assertThrows(IllegalStateException.class,
                () -> guard(true, "metrics", "prod", "tracing").verifyMockDataIsSafe());
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "dev", "test"})
    @DisplayName("permits mock data in development profiles")
    void allowsMockDataInDevelopmentProfiles(String profile) {
        assertDoesNotThrow(() -> guard(true, profile).verifyMockDataIsSafe());
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "uat", "local"})
    @DisplayName("does nothing at all when mock data is disabled")
    void allowsRealDataInEveryProfile(String profile) {
        assertDoesNotThrow(() -> guard(false, profile).verifyMockDataIsSafe());
    }

    @Test
    @DisplayName("permits mock data when no profile is active, e.g. a bare local run")
    void allowsMockDataWithNoActiveProfile() {
        assertDoesNotThrow(() -> guard(true).verifyMockDataIsSafe());
    }
}
