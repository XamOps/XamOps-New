package com.xammer.cloud.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FailureCategoryResolverTest {

    private final FailureCategoryResolver resolver = new FailureCategoryResolver();

    @Test
    void testJobName_returnsTestFailure() {
        assertThat(resolver.resolve("Run Tests", null)).isEqualTo("TEST_FAILURE");
        assertThat(resolver.resolve("unit-spec", null)).isEqualTo("TEST_FAILURE");
        assertThat(resolver.resolve("Integration Tests", null)).isEqualTo("TEST_FAILURE");
    }

    @Test
    void buildJobName_returnsBuildError() {
        assertThat(resolver.resolve("Build & Compile", null)).isEqualTo("BUILD_ERROR");
        assertThat(resolver.resolve("maven-build", null)).isEqualTo("BUILD_ERROR");
    }

    @Test
    void deployJobName_returnsDeployFailure() {
        assertThat(resolver.resolve("Deploy to UAT", null)).isEqualTo("DEPLOY_FAILURE");
        assertThat(resolver.resolve("release-pipeline", null)).isEqualTo("DEPLOY_FAILURE");
    }

    @Test
    void jenkinsUnstable_returnsTestFailure() {
        assertThat(resolver.resolve("my-job", "UNSTABLE")).isEqualTo("TEST_FAILURE");
    }

    @Test
    void unknownJob_returnsUnknown() {
        assertThat(resolver.resolve("misc-job", null)).isEqualTo("UNKNOWN");
        assertThat(resolver.resolve(null, null)).isEqualTo("UNKNOWN");
    }
}
