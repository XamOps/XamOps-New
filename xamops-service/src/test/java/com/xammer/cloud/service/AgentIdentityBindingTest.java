package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.agent.AgentPrincipal;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.repository.AgentEventRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins agent identity: which account an API key entitles its holder to act on.
 *
 * Every agent used to authenticate with one shared key that is published in the public install
 * scripts and in the frontend bundle, and identity came from {@code clusterName} /
 * {@code cloudAccountId} in the request body, which nothing verified. So the account id was
 * effectively an unauthenticated parameter: a caller could push data into another tenant's
 * dashboards, or ask {@code /api/v1/agent/config?accountId=<other>} for that tenant's
 * observability credential.
 *
 * These tests cover the two properties that fix: a scoped key resolves to a known identity, and
 * a claim outside that identity is refused.
 */
class AgentIdentityBindingTest {

    private static final String GLOBAL_KEY = "xamops_SHARED_LEGACY_KEY";
    private static final String CLUSTER_KEY = "xak_cluster_key";
    private static final String ACCOUNT_KEY = "xak_account_key";

    private static final String ACCOUNT_A = "111122223333";
    private static final String ACCOUNT_B = "999988887777";

    private AgentIngestService service;
    private KubernetesClusterConfigRepository clusterRepo;
    private CloudAccountRepository accountRepo;

    @BeforeEach
    void setUp() {
        clusterRepo = mock(KubernetesClusterConfigRepository.class);
        accountRepo = mock(CloudAccountRepository.class);
        service = new AgentIngestService(
                clusterRepo,
                mock(AgentDataCacheRepository.class),
                mock(AgentEventRepository.class),
                accountRepo);
        ReflectionTestUtils.setField(service, "globalAgentApiKey", GLOBAL_KEY);
        ReflectionTestUtils.setField(service, "legacyGlobalKeyEnabled", true);
    }

    private CloudAccount account(Long id, String awsAccountId) {
        CloudAccount a = new CloudAccount();
        a.setId(id);
        a.setAwsAccountId(awsAccountId);
        return a;
    }

    private void stubClusterKey(String key, String clusterName, Long accountPk, String awsAccountId) {
        KubernetesClusterConfig cfg = new KubernetesClusterConfig();
        cfg.setClusterName(clusterName);
        cfg.setCloudAccountId(accountPk);
        when(clusterRepo.findByAgentApiKey(key)).thenReturn(Optional.of(cfg));
        when(accountRepo.findById(accountPk)).thenReturn(Optional.of(account(accountPk, awsAccountId)));
    }

    // ── Resolution ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a per-cluster key resolves to its cluster and account")
    void clusterKeyResolvesToItsCluster() {
        // The column and repository method have existed since V20260217 and the key is already
        // handed to customers in generated Helm values — it was simply never accepted here.
        stubClusterKey(CLUSTER_KEY, "prod-eks", 7L, ACCOUNT_A);

        AgentPrincipal p = service.authenticate(CLUSTER_KEY, "10.0.0.1");

        assertThat(p).isNotNull();
        assertThat(p.getScope()).isEqualTo(AgentPrincipal.Scope.CLUSTER);
        assertThat(p.getClusterName()).isEqualTo("prod-eks");
        assertThat(p.getProviderAccountId()).isEqualTo(ACCOUNT_A);
        assertThat(p.isScoped()).isTrue();
    }

    @Test
    void accountKeyResolvesToItsAccount() {
        when(accountRepo.findByAgentApiKey(ACCOUNT_KEY))
                .thenReturn(Optional.of(account(3L, ACCOUNT_A)));

        AgentPrincipal p = service.authenticate(ACCOUNT_KEY, "10.0.0.1");

        assertThat(p).isNotNull();
        assertThat(p.getScope()).isEqualTo(AgentPrincipal.Scope.ACCOUNT);
        assertThat(p.getProviderAccountId()).isEqualTo(ACCOUNT_A);
    }

    @Test
    void unknownKeyAuthenticatesNothing() {
        assertThat(service.authenticate("xak_not_a_real_key", "10.0.0.1")).isNull();
        assertThat(service.authenticate(null, "10.0.0.1")).isNull();
        assertThat(service.authenticate("", "10.0.0.1")).isNull();
    }

    @Test
    void clusterKeyWhoseAccountIsGoneAuthenticatesNothing() {
        KubernetesClusterConfig cfg = new KubernetesClusterConfig();
        cfg.setClusterName("orphan");
        cfg.setCloudAccountId(42L);
        when(clusterRepo.findByAgentApiKey(CLUSTER_KEY)).thenReturn(Optional.of(cfg));
        when(accountRepo.findById(42L)).thenReturn(Optional.empty());

        // Better to fail authentication than to mint a principal with a null account, which
        // would then compare equal to any other request missing an account id.
        assertThat(service.authenticate(CLUSTER_KEY, "10.0.0.1")).isNull();
    }

    // ── The legacy shared key ──────────────────────────────────────────────────

    @Test
    void legacyGlobalKeyStillWorksButProvesNoIdentity() {
        AgentPrincipal p = service.authenticate(GLOBAL_KEY, "10.0.0.1");

        // Accepted so that deploying this does not take every existing agent offline.
        assertThat(p).isNotNull();
        assertThat(p.getScope()).isEqualTo(AgentPrincipal.Scope.LEGACY_GLOBAL);
        assertThat(p.isScoped()).isFalse();
        assertThat(p.getProviderAccountId()).isNull();
    }

    @Test
    @DisplayName("the shared key stops working once the flag is off")
    void legacyGlobalKeyRejectedWhenDisabled() {
        ReflectionTestUtils.setField(service, "legacyGlobalKeyEnabled", false);

        assertThat(service.authenticate(GLOBAL_KEY, "10.0.0.1")).isNull();
    }

    @Test
    void scopedKeysKeepWorkingWhenTheLegacyKeyIsDisabled() {
        ReflectionTestUtils.setField(service, "legacyGlobalKeyEnabled", false);
        stubClusterKey(CLUSTER_KEY, "prod-eks", 7L, ACCOUNT_A);

        assertThat(service.authenticate(CLUSTER_KEY, "10.0.0.1")).isNotNull();
    }

    // ── Ownership ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a cluster key cannot act on another account")
    void clusterKeyCannotActOnAnotherAccount() {
        stubClusterKey(CLUSTER_KEY, "prod-eks", 7L, ACCOUNT_A);
        AgentPrincipal p = service.authenticate(CLUSTER_KEY, "10.0.0.1");

        assertThat(service.checkOwnership(p, ACCOUNT_A, "prod-eks")).isNull();
        assertThat(service.checkOwnership(p, ACCOUNT_B, "prod-eks")).isNotNull();
    }

    @Test
    @DisplayName("a cluster key cannot act on another cluster in its own account")
    void clusterKeyCannotActOnAnotherCluster() {
        stubClusterKey(CLUSTER_KEY, "prod-eks", 7L, ACCOUNT_A);
        AgentPrincipal p = service.authenticate(CLUSTER_KEY, "10.0.0.1");

        assertThat(service.checkOwnership(p, ACCOUNT_A, "staging-eks")).isNotNull();
    }

    @Test
    void accountKeyMayActOnAnyClusterInItsOwnAccountOnly() {
        when(accountRepo.findByAgentApiKey(ACCOUNT_KEY))
                .thenReturn(Optional.of(account(3L, ACCOUNT_A)));
        AgentPrincipal p = service.authenticate(ACCOUNT_KEY, "10.0.0.1");

        assertThat(service.checkOwnership(p, ACCOUNT_A, "any-cluster")).isNull();
        assertThat(service.checkOwnership(p, ACCOUNT_A, null)).isNull();
        assertThat(service.checkOwnership(p, ACCOUNT_B, "any-cluster")).isNotNull();
    }

    @Test
    @DisplayName("AWS ids that lost their leading zeros still match")
    void leadingZeroAccountIdsAreTreatedAsEqual() {
        // cloudAccountId reaches some endpoints as a Long, which drops leading zeros, so the
        // same account arrives as both "080310056620" and "80310056620". Comparing raw strings
        // would lock out every customer whose account number starts with a zero.
        when(accountRepo.findByAgentApiKey(ACCOUNT_KEY))
                .thenReturn(Optional.of(account(3L, "080310056620")));
        AgentPrincipal p = service.authenticate(ACCOUNT_KEY, "10.0.0.1");

        assertThat(service.checkOwnership(p, "080310056620", null)).isNull();
        assertThat(service.checkOwnership(p, "80310056620", null)).isNull();
        assertThat(service.checkOwnership(p, "80310056621", null)).isNotNull();
    }

    @Test
    void scopedKeyCannotActWithoutAnAccountClaim() {
        when(accountRepo.findByAgentApiKey(ACCOUNT_KEY))
                .thenReturn(Optional.of(account(3L, ACCOUNT_A)));
        AgentPrincipal p = service.authenticate(ACCOUNT_KEY, "10.0.0.1");

        // An absent account id must not be read as "no restriction".
        assertThat(service.checkOwnership(p, null, null)).isNotNull();
        assertThat(service.checkOwnership(p, "", null)).isNotNull();
    }

    @Test
    void legacyKeyIsStillAllowedThroughOwnershipChecks() {
        AgentPrincipal p = service.authenticate(GLOBAL_KEY, "10.0.0.1");

        // Deliberate: turning this into a denial would break every un-migrated agent. Its uses
        // are logged instead, and the flag above is what finally closes it.
        assertThat(service.checkOwnership(p, ACCOUNT_B, "someone-elses-cluster")).isNull();
    }

    @Test
    void unauthenticatedRequestIsRefused() {
        assertThat(service.checkOwnership(null, ACCOUNT_A, "prod-eks")).isNotNull();
    }
}
