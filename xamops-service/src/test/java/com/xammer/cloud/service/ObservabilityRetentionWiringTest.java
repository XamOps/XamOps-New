package com.xammer.cloud.service;

import com.xammer.cloud.domain.AccountObservabilityConfig;
import com.xammer.cloud.domain.ObservabilityConfig;
import com.xammer.cloud.repository.AccountObservabilityConfigRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.repository.ObservabilityConfigRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Protects the wiring around background retention reconciliation.
 *
 * Retention has to be pushed to OpenObserve per stream, per type, per org — hundreds of
 * sequential HTTP calls for a tenant with several accounts — so it runs off the request
 * thread in {@link ObservabilityRetentionReconciler}.
 *
 * The dependency direction matters: the reconciler depends on {@link OpenObserveService},
 * never the reverse. An earlier revision had the service hold the reconciler, which is a
 * cycle that only starts with {@code @Lazy} and fails the entire application context
 * without it. This test fails if that cycle is reintroduced, which the disabled
 * full-context smoke test cannot catch.
 */
class ObservabilityRetentionWiringTest {

    @Configuration
    static class Deps {
        @Bean static PropertySourcesPlaceholderConfigurer placeholders() {
            return new PropertySourcesPlaceholderConfigurer();
        }
        @Bean ObservabilityConfigRepository configRepo() { return mock(ObservabilityConfigRepository.class); }
        @Bean AccountObservabilityConfigRepository accountConfigRepo() { return mock(AccountObservabilityConfigRepository.class); }
        @Bean CloudAccountRepository cloudAccountRepo() { return mock(CloudAccountRepository.class); }
        @Bean KubernetesClusterConfigRepository clusterConfigRepo() { return mock(KubernetesClusterConfigRepository.class); }
        @Bean RestTemplate openObserveRestTemplate() { return new RestTemplate(); }
    }

    /** Builds the real beans through their real constructors, as Spring does at startup. */
    private AnnotationConfigApplicationContext newContext() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.register(Deps.class, OpenObserveService.class, ObservabilityRetentionReconciler.class);
        ctx.refresh();
        return ctx;
    }

    @Test
    void bothBeansStartWithoutACircularDependency() {
        assertThatCode(() -> {
            try (AnnotationConfigApplicationContext ctx = newContext()) {
                assertThat(ctx.getBean(OpenObserveService.class)).isNotNull();
                assertThat(ctx.getBean(ObservabilityRetentionReconciler.class)).isNotNull();
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void listOrgIdsForClientIncludesTheClientOrgAndEveryAccountOrg() {
        try (AnnotationConfigApplicationContext ctx = newContext()) {
            ObservabilityConfigRepository configRepo = ctx.getBean(ObservabilityConfigRepository.class);
            AccountObservabilityConfigRepository accountRepo =
                    ctx.getBean(AccountObservabilityConfigRepository.class);

            ObservabilityConfig clientConfig = new ObservabilityConfig();
            clientConfig.setO2OrgId("client-org");
            when(configRepo.findByClientId(anyLong())).thenReturn(Optional.of(clientConfig));

            AccountObservabilityConfig acctA = new AccountObservabilityConfig();
            acctA.setO2OrgId("acct-org-a");
            AccountObservabilityConfig acctB = new AccountObservabilityConfig();
            acctB.setO2OrgId("acct-org-b");
            // A null org id means the account never finished provisioning — must be skipped
            // rather than producing a request to /api/null/settings.
            AccountObservabilityConfig unprovisioned = new AccountObservabilityConfig();
            when(accountRepo.findByClientId(anyLong()))
                    .thenReturn(List.of(acctA, acctB, unprovisioned));

            // Agent data lands in the per-account orgs, so retention that only reached the
            // client-level org left the orgs actually storing metrics untouched.
            assertThat(ctx.getBean(OpenObserveService.class).listOrgIdsForClient(1L))
                    .containsExactly("client-org", "acct-org-a", "acct-org-b");
        }
    }

    @Test
    void listOrgIdsForClientDeduplicatesSharedOrgIds() {
        try (AnnotationConfigApplicationContext ctx = newContext()) {
            ObservabilityConfig clientConfig = new ObservabilityConfig();
            clientConfig.setO2OrgId("shared-org");
            when(ctx.getBean(ObservabilityConfigRepository.class).findByClientId(anyLong()))
                    .thenReturn(Optional.of(clientConfig));

            AccountObservabilityConfig sameOrg = new AccountObservabilityConfig();
            sameOrg.setO2OrgId("shared-org");
            when(ctx.getBean(AccountObservabilityConfigRepository.class).findByClientId(anyLong()))
                    .thenReturn(List.of(sameOrg));

            // Single-account tenants can share one org; applying retention twice is wasted
            // HTTP against a service we already know is a bottleneck.
            assertThat(ctx.getBean(OpenObserveService.class).listOrgIdsForClient(1L))
                    .containsExactly("shared-org");
        }
    }

    @Test
    void defaultRetentionMatchesTheEntityDefault() {
        // A per-account org provisioned before its client config exists falls back to this
        // constant. If it drifts below the entity default, accounts silently inherit shorter
        // retention than the UI advertises — exactly the bug this constant was added to fix.
        assertThat(OpenObserveService.DEFAULT_RETENTION_DAYS)
                .isEqualTo(new ObservabilityConfig().getRetentionDays());
    }
}
