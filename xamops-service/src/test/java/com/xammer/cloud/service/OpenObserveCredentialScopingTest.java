package com.xammer.cloud.service;

import com.xammer.cloud.domain.AccountObservabilityConfig;
import com.xammer.cloud.domain.ObservabilityConfig;
import com.xammer.cloud.repository.AccountObservabilityConfigRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.repository.ObservabilityConfigRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.mock.env.MockPropertySource;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the tenant-isolation invariant for collector configuration.
 *
 * Every config XamOps hands a customer to install used to embed
 * {@code base64(openobserve.admin.email:openobserve.admin.password)}. In OpenObserve the org is
 * only a path segment and the root account is global, so a customer holding any of those files
 * could change the org in the URL and read or write every other tenant's logs, metrics and
 * traces. The credential was also identical for every tenant, so one leaked agent config
 * compromised the whole fleet.
 *
 * These tests assert the two halves of the fix: generators emit the org-scoped ingestion
 * credential, and they refuse outright when one does not exist rather than reaching for admin.
 * The admin-credential assertions are deliberately literal — they are what would catch a future
 * "just fall back so provisioning stops failing" change.
 */
class OpenObserveCredentialScopingTest {

    private static final String ADMIN_EMAIL = "admin@xamops.invalid";
    private static final String ADMIN_PASSWORD = "admin-secret-do-not-ship";

    /** What a provisioned org's ingestion service account looks like once stored. */
    private static final String SCOPED_USER = "ingest-acc-42@xamops.internal";
    private static final String SCOPED_TOKEN = "scoped-token-abc123";
    private static final String SCOPED_KEY = Base64.getEncoder()
            .encodeToString((SCOPED_USER + ":" + SCOPED_TOKEN).getBytes(StandardCharsets.UTF_8));

    private static final String ADMIN_KEY = Base64.getEncoder()
            .encodeToString((ADMIN_EMAIL + ":" + ADMIN_PASSWORD).getBytes(StandardCharsets.UTF_8));

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

    private AnnotationConfigApplicationContext newContext() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MockPropertySource()
                .withProperty("openobserve.url", "http://openobserve.invalid:5080")
                .withProperty("openobserve.external.url", "https://otel.xamops.invalid")
                .withProperty("openobserve.admin.email", ADMIN_EMAIL)
                .withProperty("openobserve.admin.password", ADMIN_PASSWORD));
        ctx.register(Deps.class, OpenObserveService.class);
        ctx.refresh();
        return ctx;
    }

    /**
     * A client-level config that is fully provisioned, with a scoped ingestion key.
     *
     * Stubs both lookups: the generators resolve by client id, while getAgentConfig has no
     * client id and uses the "exactly one config row" heuristic over findAll.
     */
    private void stubProvisionedClient(AnnotationConfigApplicationContext ctx, String ingestionKey) {
        ObservabilityConfig cfg = new ObservabilityConfig();
        cfg.setO2OrgId("org_tenant_a");
        cfg.setO2IngestionKey(ingestionKey);
        ObservabilityConfigRepository repo = ctx.getBean(ObservabilityConfigRepository.class);
        when(repo.findByClientId(anyLong())).thenReturn(Optional.of(cfg));
        when(repo.findAll(org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(cfg)));
    }

    // ── Generators emit the scoped credential, never admin ─────────────────────

    @Test
    @DisplayName("no generator embeds the OpenObserve admin credential")
    void generatorsUseTheScopedIngestionCredential() {
        try (AnnotationConfigApplicationContext ctx = newContext()) {
            stubProvisionedClient(ctx, SCOPED_KEY);
            OpenObserveService svc = ctx.getBean(OpenObserveService.class);

            String agentSecret = svc.generateAgentSecret(1L, null);
            String helmEks     = svc.generateHelmEksCommand(1L, null);
            String ec2         = svc.generateEc2OtelConfig(1L, null);
            String gce         = svc.generateGceOtelConfig(1L, null);
            String fluentBit   = svc.generateFluentBitConfig(1L, null);
            String helmValues  = svc.generateHelmValues(1L, null);
            String otel        = svc.generateOtelCollectorConfig(1L, null);

            for (String out : new String[]{ agentSecret, helmEks, ec2, gce, fluentBit, helmValues, otel }) {
                assertThat(out)
                        .doesNotContain(ADMIN_PASSWORD)
                        .doesNotContain(ADMIN_EMAIL)
                        .doesNotContain(ADMIN_KEY);
            }

            // And the scoped credential is actually present in each.
            assertThat(agentSecret).contains(SCOPED_KEY).contains(SCOPED_USER).contains(SCOPED_TOKEN);
            assertThat(helmEks).contains(SCOPED_KEY);
            assertThat(ec2).contains(SCOPED_KEY);
            assertThat(gce).contains(SCOPED_KEY);
            assertThat(otel).contains(SCOPED_KEY);
        }
    }

    @Test
    @DisplayName("VM install scripts lock down the credential-bearing config file")
    void vmScriptsRestrictConfigPermissions() {
        try (AnnotationConfigApplicationContext ctx = newContext()) {
            stubProvisionedClient(ctx, SCOPED_KEY);
            OpenObserveService svc = ctx.getBean(OpenObserveService.class);

            // Verified the hard way: a UAT host had /etc/otelcol-contrib/config.yaml at 0644
            // with the OpenObserve credential in cleartext, readable by every local user. `tee`
            // does not restrict it, so the script has to.
            for (String script : new String[]{
                    svc.generateEc2OtelConfig(1L, null),
                    svc.generateGceOtelConfig(1L, null) }) {
                assertThat(script)
                        .contains("chmod 640 /etc/otelcol-contrib/config.yaml")
                        .contains("chown root:otelcol-contrib /etc/otelcol-contrib/config.yaml");

                // Also verified the hard way: 0600 root:root stopped the collector starting,
                // because it runs as the unprivileged otelcol-contrib user and could not read
                // its own configuration.
                assertThat(script).doesNotContain("chmod 600 /etc/otelcol-contrib/config.yaml");
            }
        }
    }

    @Test
    @DisplayName("Beyla is stopped before its binary is replaced")
    void ec2ScriptStopsBeylaBeforeUpgrading() {
        try (AnnotationConfigApplicationContext ctx = newContext()) {
            stubProvisionedClient(ctx, SCOPED_KEY);
            String script = ctx.getBean(OpenObserveService.class).generateEc2OtelConfig(1L, null);

            // Copying over a running executable fails with "Text file busy". The copy was
            // unchecked, so a re-run reported success while leaving the old binary in place.
            assertThat(script).contains("systemctl stop beyla");
            assertThat(script.indexOf("systemctl stop beyla"))
                    .isLessThan(script.indexOf("cp \"$beyla_src\" /usr/local/bin/beyla"));

            // These steps are pasted into an interactive root shell, so a bare `exit` on a
            // missing binary would close the operator's session instead of skipping a step.
            assertThat(script).doesNotContain("\n  exit 1\n");
        }
    }

    @Test
    @DisplayName("docker log access grants traverse on the parent, and reports failure")
    void dockerAclGrantsParentTraverse() {
        try (AnnotationConfigApplicationContext ctx = newContext()) {
            stubProvisionedClient(ctx, SCOPED_KEY);
            OpenObserveService svc = ctx.getBean(OpenObserveService.class);

            for (String script : new String[]{
                    svc.generateEc2OtelConfig(1L, null),
                    svc.generateGceOtelConfig(1L, null) }) {
                // /var/lib/docker is 0710 root:root — without the traverse bit the receiver
                // reports "open .: permission denied" even though the ACL on containers/ applied.
                assertThat(script).contains("setfacl -m  u:otelcol-contrib:x  /var/lib/docker");
                // And the outcome is no longer swallowed by `2>/dev/null || true`.
                assertThat(script).contains("docker_acl_ok");
            }
        }
    }

    @Test
    @DisplayName("Fluent Bit user and password are halves of the same credential")
    void fluentBitCredentialHalvesMatch() {
        try (AnnotationConfigApplicationContext ctx = newContext()) {
            stubProvisionedClient(ctx, SCOPED_KEY);
            OpenObserveService svc = ctx.getBean(OpenObserveService.class);

            // Previously Http_User was the admin email while Http_Passwd was the service
            // account's password — a pair that authenticates as neither identity, so the
            // generated config silently shipped nothing.
            assertThat(svc.generateFluentBitConfig(1L, null))
                    .contains("Http_User       " + SCOPED_USER)
                    .contains("Http_Passwd     " + SCOPED_TOKEN);
            assertThat(svc.generateHelmValues(1L, null))
                    .contains("Http_User        " + SCOPED_USER)
                    .contains("Http_Passwd      " + SCOPED_TOKEN);
        }
    }

    @Test
    void agentConfigReturnsTheScopedIdentityNotAdmin() {
        try (AnnotationConfigApplicationContext ctx = newContext()) {
            stubProvisionedClient(ctx, SCOPED_KEY);

            var cfg = ctx.getBean(OpenObserveService.class).getAgentConfig(null);

            assertThat(cfg.get("org")).isEqualTo("org_tenant_a");
            assertThat(cfg.get("username")).isEqualTo(SCOPED_USER);
            assertThat(cfg.get("password")).isEqualTo(SCOPED_TOKEN);
            assertThat(cfg.values()).doesNotContain(ADMIN_EMAIL, ADMIN_PASSWORD);
        }
    }

    @Test
    void accountScopedKeyWinsOverTheClientLevelOne() {
        try (AnnotationConfigApplicationContext ctx = newContext()) {
            stubProvisionedClient(ctx, SCOPED_KEY);

            String acctUser = "ingest-acc-99@xamops.internal";
            String acctToken = "per-account-token";
            String acctKey = Base64.getEncoder()
                    .encodeToString((acctUser + ":" + acctToken).getBytes(StandardCharsets.UTF_8));

            AccountObservabilityConfig acc = new AccountObservabilityConfig();
            acc.setO2OrgId("org_account_99");
            acc.setO2IngestionKey(acctKey);
            when(ctx.getBean(AccountObservabilityConfigRepository.class)
                    .findByClientIdAndProviderAccountId(anyLong(), org.mockito.ArgumentMatchers.eq("111122223333")))
                    .thenReturn(Optional.of(acc));

            String out = ctx.getBean(OpenObserveService.class).generateEc2OtelConfig(1L, "111122223333");

            assertThat(out).contains("org_account_99").contains(acctKey);
            assertThat(out).doesNotContain(SCOPED_KEY).doesNotContain(ADMIN_PASSWORD);
        }
    }

    // ── Refusal instead of falling back to admin ───────────────────────────────

    @Test
    @DisplayName("provisioning repairs a poisoned key instead of returning early")
    void provisioningRepairsAnExistingOrgWithAnAdminIngestionKey() {
        try (AnnotationConfigApplicationContext ctx = newContext()) {
            ObservabilityConfig cfg = new ObservabilityConfig();
            cfg.setO2OrgId("org_tenant_a");
            cfg.setO2IngestionKey(ADMIN_KEY);
            ObservabilityConfigRepository repo = ctx.getBean(ObservabilityConfigRepository.class);
            when(repo.findByClientId(anyLong())).thenReturn(Optional.of(cfg));

            com.xammer.cloud.domain.Client client = new com.xammer.cloud.domain.Client();
            client.setId(1L);
            client.setName("tenant-a");

            // provisionTenant returned early on any non-null orgId, so a row poisoned by the old
            // fallback could never be fixed through the UI — the Observability tab would keep
            // refusing to generate a config with no way to resolve it short of a manual UPDATE.
            // It must now attempt a repair. OpenObserve is unreachable in this test, so the
            // repair cannot succeed; what matters is that it tried and did not hand back the
            // admin credential as though it were valid.
            ctx.getBean(OpenObserveService.class).provisionTenant(client);

            assertThat(cfg.getO2IngestionKey())
                    .describedAs("must not silently keep serving the admin credential")
                    .satisfiesAnyOf(
                            key -> assertThat(key).isNull(),
                            key -> assertThat(key).isEqualTo(ADMIN_KEY)); // unchanged only because O2 is down

            // And the generators still refuse, rather than emitting it.
            assertThatThrownBy(() -> ctx.getBean(OpenObserveService.class).generateEc2OtelConfig(1L, null))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("an ingestion key that is really the admin credential is refused")
    void refusesAnIngestionKeyPoisonedByTheOldFallback() {
        try (AnnotationConfigApplicationContext ctx = newContext()) {
            // What every tenant provisioned before the fallback was removed actually has stored:
            // createServiceAccount returned base64(admin:password) on failure and the caller
            // persisted it as o2_ingestion_key. Without this check those tenants keep being
            // issued root credentials and the "no admin in output" assertions above would still
            // pass, because the admin credential arrives disguised as a scoped key.
            stubProvisionedClient(ctx, ADMIN_KEY);
            OpenObserveService svc = ctx.getBean(OpenObserveService.class);

            assertThatThrownBy(() -> svc.generateEc2OtelConfig(1L, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("admin credential");
            assertThatThrownBy(() -> svc.generateAgentSecret(1L, null))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> svc.getAgentConfig(null))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("a missing ingestion key fails the request instead of substituting admin")
    void refusesToIssueConfigWithoutAScopedCredential() {
        try (AnnotationConfigApplicationContext ctx = newContext()) {
            // Org provisioned, but the service account was never created — the exact state the
            // old silent fallback produced and then persisted.
            stubProvisionedClient(ctx, null);
            OpenObserveService svc = ctx.getBean(OpenObserveService.class);

            assertThatThrownBy(() -> svc.generateEc2OtelConfig(1L, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ingestion credential");
            assertThatThrownBy(() -> svc.generateAgentSecret(1L, null))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> svc.generateHelmEksCommand(1L, null))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> svc.generateGceOtelConfig(1L, null))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> svc.generateFluentBitConfig(1L, null))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> svc.generateOtelCollectorConfig(1L, null))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void agentConfigRefusesWhenNothingIsProvisioned() {
        try (AnnotationConfigApplicationContext ctx = newContext()) {
            // No config rows at all — an agent asking before onboarding finished. This used to
            // return the admin credential pointed at the shared "default" org.
            when(ctx.getBean(ObservabilityConfigRepository.class)
                    .findAll(org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class)))
                    .thenReturn(org.springframework.data.domain.Page.empty());

            assertThatThrownBy(() -> ctx.getBean(OpenObserveService.class).getAgentConfig(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not provisioned");
        }
    }
}
