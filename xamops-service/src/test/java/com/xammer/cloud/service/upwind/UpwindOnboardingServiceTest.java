package com.xammer.cloud.service.upwind;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.UpwindAccountConnection;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.UpwindAccountConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UpwindOnboardingServiceTest {

    private CloudAccountRepository cloudAccountRepository;
    private UpwindAccountConnectionRepository connectionRepo;
    private UpwindApiClient apiClient;
    private UpwindIntegrationClient integrationClient;
    private com.xammer.cloud.repository.ClientRepository clientRepository;
    private UpwindOnboardingService service;

    private static final Long CLIENT = 1L;
    private static final String ACCT = "605134457560";

    @BeforeEach
    void setUp() {
        cloudAccountRepository = mock(CloudAccountRepository.class);
        connectionRepo = mock(UpwindAccountConnectionRepository.class);
        apiClient = mock(UpwindApiClient.class);
        integrationClient = mock(UpwindIntegrationClient.class);
        clientRepository = mock(com.xammer.cloud.repository.ClientRepository.class);
        // Entitled by default; the restriction is the exception, so tests opt into it.
        com.xammer.cloud.domain.Client entitled = new com.xammer.cloud.domain.Client();
        entitled.setXamSecureOnboardingEnabled(true);
        when(clientRepository.findById(CLIENT)).thenReturn(Optional.of(entitled));
        service = new UpwindOnboardingService(cloudAccountRepository, connectionRepo, apiClient,
                integrationClient, clientRepository);
        ReflectionTestUtils.setField(service, "templateUrl",
                "https://s3.amazonaws.com/cfn.upwind.io/onboarding/latest.yaml");
        ReflectionTestUtils.setField(service, "trustedAccount", "340457201789");
        ReflectionTestUtils.setField(service, "upwindRegion", "us");
        ReflectionTestUtils.setField(service, "stackRegion", "us-east-1");
        ReflectionTestUtils.setField(service, "onboardingClientId", "cloud-acct-id");
        ReflectionTestUtils.setField(service, "onboardingClientSecret", "cloud-acct-secret");
        ReflectionTestUtils.setField(service, "orgId", "org_Zwq0e1xF9Au4AF0Q");
        when(connectionRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    /** getProviderAccountId() is derived from the provider, so both fields have to be set. */
    private void ownsAws() {
        CloudAccount a = new CloudAccount();
        a.setAwsAccountId(ACCT);
        a.setProvider("AWS");
        a.setAccountName("Customer Prod");
        when(cloudAccountRepository.findByClientId(CLIENT)).thenReturn(List.of(a));
    }

    @Test
    void launchUrlCarriesEveryParameterTheTemplateRequires() {
        ownsAws();
        when(connectionRepo.findByCloudAccountId(ACCT)).thenReturn(Optional.empty());

        String url = (String) service.createLaunchUrl(CLIENT, ACCT).get("launchUrl");

        assertTrue(url.startsWith("https://us-east-1.console.aws.amazon.com/cloudformation/home"), url);
        assertTrue(url.contains("#/stacks/quickcreate?"), url);
        assertTrue(url.contains("templateURL=https%3A%2F%2Fs3.amazonaws.com%2Fcfn.upwind.io"), url);
        assertTrue(url.contains("param_OrganizationId=org_Zwq0e1xF9Au4AF0Q"), url);
        assertTrue(url.contains("param_TrustedAccount=340457201789"), url);
        assertTrue(url.contains("param_UpwindRegion=us"), url);
        assertTrue(url.contains("param_ClientId=cloud-acct-id"), url);
        assertTrue(url.contains("param_ClientSecret=cloud-acct-secret"), url);
    }

    /**
     * The template's Rules assertion accepts EITHER the client id/secret pair OR
     * CredentialsSecretArn+KmsKeyArn. Sending a half-set secret-ARN path would fail the
     * assertion, so those two parameters must be left at their NOT_SET defaults entirely.
     */
    @Test
    void launchUrlLeavesTheSecretArnPathUntouched() {
        ownsAws();
        when(connectionRepo.findByCloudAccountId(ACCT)).thenReturn(Optional.empty());

        String url = (String) service.createLaunchUrl(CLIENT, ACCT).get("launchUrl");

        assertFalse(url.contains("param_CredentialsSecretArn"), url);
        assertFalse(url.contains("param_KmsKeyArn"), url);
    }

    /**
     * Stack name is derived from the account id, not a timestamp, so re-running onboarding
     * updates the existing stack. Two stacks in one account would both try to register it and
     * collide on the role name.
     */
    @Test
    void stackNameIsStableForAnAccount() {
        ownsAws();
        when(connectionRepo.findByCloudAccountId(ACCT)).thenReturn(Optional.empty());

        assertEquals("upwind-readonly-permissions-" + ACCT,
                service.createLaunchUrl(CLIENT, ACCT).get("stackName"));
        assertEquals("upwind-readonly-permissions-" + ACCT,
                service.createLaunchUrl(CLIENT, ACCT).get("stackName"));
    }

    @Test
    void launchUrlMarksTheConnectionPendingWithItsRegionAndStack() {
        ownsAws();
        when(connectionRepo.findByCloudAccountId(ACCT)).thenReturn(Optional.empty());

        service.createLaunchUrl(CLIENT, ACCT);

        verify(connectionRepo).save(argThat(c ->
                "PENDING".equals(c.getStatus())
                        && ACCT.equals(c.getCloudAccountId())
                        && "us-east-1".equals(c.getStackRegion())
                        && c.getStackRequestedAt() != null));
    }

    /** Re-running onboarding on a live account (e.g. a stack update for new permissions)
     *  must not knock it back to PENDING and blank the tab out. */
    @Test
    void reRunningOnAConnectedAccountKeepsItConnected() {
        ownsAws();
        UpwindAccountConnection existing = new UpwindAccountConnection();
        existing.setCloudAccountId(ACCT);
        existing.setStatus("CONNECTED");
        when(connectionRepo.findByCloudAccountId(ACCT)).thenReturn(Optional.of(existing));

        service.createLaunchUrl(CLIENT, ACCT);

        verify(connectionRepo).save(argThat(c -> "CONNECTED".equals(c.getStatus())));
    }

    /** Tenancy: the account id off the request is never trusted on its own. */
    @Test
    void refusesAnAccountTheClientDoesNotOwn() {
        when(cloudAccountRepository.findByClientId(CLIENT)).thenReturn(List.of());
        assertThrows(AccessDeniedException.class,
                () -> service.createLaunchUrl(CLIENT, "999999999999"));
    }

    @Test
    void refusesNonAwsAccounts() {
        CloudAccount gcp = new CloudAccount();
        gcp.setGcpProjectId("my-gcp-project");
        gcp.setProvider("GCP");
        when(cloudAccountRepository.findByClientId(CLIENT)).thenReturn(List.of(gcp));

        assertThrows(IllegalArgumentException.class,
                () -> service.createLaunchUrl(CLIENT, "my-gcp-project"));
    }

    @Test
    void refusesWhenTheSharedCredentialIsNotConfigured() {
        ReflectionTestUtils.setField(service, "onboardingClientSecret", "");
        ownsAws();
        assertThrows(IllegalStateException.class, () -> service.createLaunchUrl(CLIENT, ACCT));
    }

    /**
     * cloud_account_id must be the ONLY condition. Pairing it with cloud_provider returns 200
     * with an empty items[], which would read as "never connected" for every account forever.
     */
    @Test
    void verificationQueriesOnlyOnCloudAccountId() throws Exception {
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.searchInventory(any(), anyInt())).thenReturn(
                List.of(new ObjectMapper().readTree("{\"id\":\"asset-1\"}")));

        assertTrue(service.isLiveInUpwind(ACCT));

        verify(apiClient).searchInventory(argThat(conds ->
                conds.size() == 1
                        && "cloud_account_id".equals(conds.get(0).get("field"))
                        && "eq".equals(conds.get(0).get("operator"))
                        && List.of(ACCT).equals(conds.get(0).get("value"))), anyInt());
    }

    @Test
    void promotesPendingConnectionsThatAreReportingAssets() throws Exception {
        UpwindAccountConnection pending = new UpwindAccountConnection();
        pending.setCloudAccountId(ACCT);
        pending.setStatus("PENDING");
        pending.setStackRequestedAt(Instant.now());
        when(connectionRepo.findByStatusNot("CONNECTED")).thenReturn(List.of(pending));
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.searchInventory(any(), anyInt())).thenReturn(
                List.of(new ObjectMapper().readTree("{\"id\":\"asset-1\"}")));

        assertEquals(1, service.verifyPendingConnections());
        verify(connectionRepo).save(argThat(c ->
                "CONNECTED".equals(c.getStatus()) && c.getConnectionVerifiedAt() != null));
    }

    /** Upwind reports nothing for an account with no scannable resources yet, so silence must
     *  never be read as disconnection — otherwise the badge flaps. */
    @Test
    void neverDemotesAConnectionWhenUpwindReportsNothing() {
        when(connectionRepo.findByStatusNot("CONNECTED")).thenReturn(List.of());
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.searchInventory(any(), anyInt())).thenReturn(List.of());

        assertEquals(0, service.verifyPendingConnections());
        verify(connectionRepo, never()).save(any());
    }

    /** A transient API failure is "unknown", not "not connected". */
    @Test
    void treatsAVerificationFailureAsUnknown() {
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.searchInventory(any(), anyInt()))
                .thenThrow(new IllegalStateException("upstream down"));

        assertFalse(service.isLiveInUpwind(ACCT));
    }

    /**
     * A stack that deployed cleanly registers immediately, so a long silence means a failed or
     * abandoned stack. The message has to send people to CloudFormation, not to support.
     */
    @Test
    void tellsTheUserToCheckTheStackOnceTheGracePeriodHasPassed() {
        ownsAws();
        UpwindAccountConnection stale = new UpwindAccountConnection();
        stale.setCloudAccountId(ACCT);
        stale.setStatus("PENDING");
        stale.setStackName("upwind-readonly-permissions-" + ACCT);
        stale.setStackRegion("us-east-1");
        stale.setStackRequestedAt(Instant.now().minusSeconds(
                (UpwindOnboardingService.STACK_GRACE_MINUTES + 5) * 60));
        when(connectionRepo.findByCloudAccountId(ACCT)).thenReturn(Optional.of(stale));
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.searchInventory(any(), anyInt())).thenReturn(List.of());

        Map<String, Object> out = service.checkConnection(CLIENT, ACCT);

        assertEquals("PENDING", out.get("status"));
        assertEquals(false, out.get("reportingAssets"));
        String msg = (String) out.get("message");
        assertTrue(msg.contains("CREATE_COMPLETE"), msg);
        assertTrue(msg.contains("upwind-readonly-permissions-" + ACCT), msg);
    }

    @Test
    void reportsFreshStacksAsStillInProgress() {
        ownsAws();
        UpwindAccountConnection fresh = new UpwindAccountConnection();
        fresh.setCloudAccountId(ACCT);
        fresh.setStatus("PENDING");
        fresh.setStackRequestedAt(Instant.now());
        when(connectionRepo.findByCloudAccountId(ACCT)).thenReturn(Optional.of(fresh));
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.searchInventory(any(), anyInt())).thenReturn(List.of());

        String msg = (String) service.checkConnection(CLIENT, ACCT).get("message");
        assertTrue(msg.contains("few minutes"), msg);
    }

    @Test
    void checkPromotesTheConnectionWhenAssetsAppear() throws Exception {
        ownsAws();
        UpwindAccountConnection pending = new UpwindAccountConnection();
        pending.setCloudAccountId(ACCT);
        pending.setStatus("PENDING");
        pending.setStackRequestedAt(Instant.now());
        when(connectionRepo.findByCloudAccountId(ACCT)).thenReturn(Optional.of(pending));
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.searchInventory(any(), anyInt())).thenReturn(
                List.of(new ObjectMapper().readTree("{\"id\":\"asset-1\"}")));

        Map<String, Object> out = service.checkConnection(CLIENT, ACCT);

        assertEquals("CONNECTED", out.get("status"));
        assertEquals(true, out.get("reportingAssets"));
        verify(connectionRepo).save(argThat(c -> "CONNECTED".equals(c.getStatus())));
    }

    @Test
    void checkRefusesAnAccountTheClientDoesNotOwn() {
        when(cloudAccountRepository.findByClientId(CLIENT)).thenReturn(List.of());
        assertThrows(AccessDeniedException.class,
                () -> service.checkConnection(CLIENT, "999999999999"));
    }

    // ── Sensor install command ──

    @Test
    void sensorNotConfiguredByDefault() {
        assertFalse(service.isSensorConfigured());
        assertThrows(IllegalStateException.class, service::renderSensorInstallCommand);
    }

    /**
     * Copied verbatim from Upwind's "Connect a host" console flow (Step 3), not reverse
     * engineered — the flag names, scanner-v2 extra config and pipe-to-bash shape are Upwind's.
     * This pins that shape so an edit here has to be a deliberate change, not a typo.
     */
    @Test
    void rendersUpwindsOwnSensorScriptWithTheSharedCredentialFilledIn() {
        ReflectionTestUtils.setField(service, "sensorClientId", "512eba83-7fb5-4ded-b5e0-2b09f9d6af02");
        ReflectionTestUtils.setField(service, "sensorClientSecret", "sensor-secret-value");

        assertTrue(service.isSensorConfigured());
        String cmd = service.renderSensorInstallCommand();

        assertTrue(cmd.contains("export CLIENT_ID=\"512eba83-7fb5-4ded-b5e0-2b09f9d6af02\""), cmd);
        assertTrue(cmd.contains("export CLIENT_SECRET=\"sensor-secret-value\""), cmd);
        assertTrue(cmd.contains("curl -s https://get.upwind.io/sensor.sh"), cmd);
        assertTrue(cmd.contains("UPWIND_CLIENT_ID=\"$CLIENT_ID\""), cmd);
        assertTrue(cmd.contains("UPWIND_CLIENT_SECRET=\"$CLIENT_SECRET\""), cmd);
        assertTrue(cmd.contains("UPWIND_AGENT_EXTRA_CONFIG=\"scanner-v2=true\""), cmd);
        assertTrue(cmd.contains("bash -s"), cmd);
    }

    // ── BillOps per-client entitlement ──

    /** Missing client row reads as allowed, matching the column default: a lookup miss must not
     *  become a silent feature withdrawal for everyone. */
    @Test
    void treatsAnUnknownClientAsAllowed() {
        when(clientRepository.findById(99L)).thenReturn(Optional.empty());
        assertTrue(service.isAllowedForClient(99L));
    }

    @Test
    void refusesOnboardingWhenTheClientIsRestricted() {
        com.xammer.cloud.domain.Client restricted = new com.xammer.cloud.domain.Client();
        restricted.setXamSecureOnboardingEnabled(false);
        when(clientRepository.findById(CLIENT)).thenReturn(Optional.of(restricted));
        ownsAws();

        assertThrows(AccessDeniedException.class, () -> service.createLaunchUrl(CLIENT, ACCT));
        assertFalse(service.isAllowedForClient(CLIENT));
    }

    /** Restriction gates NEW connections only — it must not block a customer from disconnecting
     *  an account they already have, which would trap them. */
    @Test
    void stillAllowsDisconnectWhenTheClientIsRestricted() {
        com.xammer.cloud.domain.Client restricted = new com.xammer.cloud.domain.Client();
        restricted.setXamSecureOnboardingEnabled(false);
        when(clientRepository.findById(CLIENT)).thenReturn(Optional.of(restricted));
        ownsAws();
        when(integrationClient.isConfigured()).thenReturn(true);
        when(connectionRepo.findByCloudAccountId(ACCT)).thenReturn(Optional.of(connected("cred-1")));
        when(integrationClient.deleteCloudCredential("cred-1")).thenReturn(true);

        assertEquals(true, service.disconnect(CLIENT, ACCT).get("deregistered"));
    }

    // ── Disconnection ──

    private UpwindAccountConnection connected(String credentialId) {
        UpwindAccountConnection c = new UpwindAccountConnection();
        c.setCloudAccountId(ACCT);
        c.setStatus("CONNECTED");
        c.setUpwindCredentialId(credentialId);
        c.setStackName("upwind-readonly-permissions-" + ACCT);
        c.setStackRegion("us-east-1");
        return c;
    }

    @Test
    void disconnectDeregistersUpstreamAndClearsLocalState() {
        ownsAws();
        when(integrationClient.isConfigured()).thenReturn(true);
        when(connectionRepo.findByCloudAccountId(ACCT)).thenReturn(Optional.of(connected("cred-1")));
        when(integrationClient.deleteCloudCredential("cred-1")).thenReturn(true);

        Map<String, Object> out = service.disconnect(CLIENT, ACCT);

        verify(integrationClient).deleteCloudCredential("cred-1");
        assertEquals("NOT_CONNECTED", out.get("status"));
        assertEquals(true, out.get("deregistered"));
        // The customer still has to delete the stack; the message must say so, because Upwind's
        // template Delete handler does nothing and the IAM role would otherwise linger.
        assertTrue(((String) out.get("message")).contains("CloudFormation"), out.get("message").toString());
        verify(connectionRepo).save(argThat(c ->
                "NOT_CONNECTED".equals(c.getStatus())
                        && c.getUpwindCredentialId() == null
                        && c.getConnectionVerifiedAt() == null
                        && c.getDisconnectedAt() != null));
    }

    /**
     * Connections made before this feature existed (or through the Upwind console) have no stored
     * credential id, and those are exactly the ones most likely to need removing.
     */
    @Test
    void disconnectResolvesTheCredentialIdFromUpwindWhenNotStored() {
        ownsAws();
        when(integrationClient.isConfigured()).thenReturn(true);
        UpwindAccountConnection noId = connected(null);
        when(connectionRepo.findByCloudAccountId(ACCT)).thenReturn(Optional.of(noId));
        when(integrationClient.findByCloudAccountId(ACCT)).thenReturn(Optional.of(
                new UpwindIntegrationClient.CloudCredential(
                        "cred-from-upwind", "n", "connected", ACCT, true, true)));
        when(integrationClient.deleteCloudCredential("cred-from-upwind")).thenReturn(true);

        service.disconnect(CLIENT, ACCT);

        verify(integrationClient).deleteCloudCredential("cred-from-upwind");
    }

    /** Nothing registered upstream: still clear local state, or the UI keeps claiming protection. */
    @Test
    void disconnectClearsLocalStateWhenNothingIsRegisteredUpstream() {
        ownsAws();
        when(integrationClient.isConfigured()).thenReturn(true);
        when(connectionRepo.findByCloudAccountId(ACCT)).thenReturn(Optional.of(connected(null)));
        when(integrationClient.findByCloudAccountId(ACCT)).thenReturn(Optional.empty());

        Map<String, Object> out = service.disconnect(CLIENT, ACCT);

        verify(integrationClient, never()).deleteCloudCredential(anyString());
        assertEquals(false, out.get("deregistered"));
        verify(connectionRepo).save(argThat(c -> "NOT_CONNECTED".equals(c.getStatus())));
    }

    @Test
    void disconnectRefusesAnAccountTheClientDoesNotOwn() {
        when(cloudAccountRepository.findByClientId(CLIENT)).thenReturn(List.of());
        assertThrows(AccessDeniedException.class,
                () -> service.disconnect(CLIENT, "999999999999"));
    }

    /** A deliberate disconnect must not be undone by the verifier: Upwind can keep reporting
     *  assets for minutes afterwards. */
    @Test
    void verifierDoesNotResurrectADisconnectedAccount() {
        UpwindAccountConnection gone = new UpwindAccountConnection();
        gone.setCloudAccountId(ACCT);
        gone.setStatus("NOT_CONNECTED");
        gone.setDisconnectedAt(Instant.now());
        when(connectionRepo.findByStatusNot("CONNECTED")).thenReturn(List.of(gone));

        assertEquals(0, service.verifyPendingConnections());
        verify(connectionRepo, never()).save(any());
        verifyNoInteractions(integrationClient);
    }

    // ── Reconciliation with accounts connected outside XamOps ──

    /**
     * The Xammer account was connected in the Upwind console long before this feature, so it has
     * no local row — and it displayed "Not protected" for ever, because the pending sweep only
     * reads rows that already exist and the UI mapped a missing row to NOT_CONNECTED.
     */
    @Test
    void adoptsARegistrationThatHasNoLocalRow() {
        when(integrationClient.isConfigured()).thenReturn(true);
        when(integrationClient.listCloudCredentials()).thenReturn(List.of(
                new UpwindIntegrationClient.CloudCredential(
                        "cred-x", "sahil127", "connected", ACCT, true, true)));
        when(connectionRepo.findByCloudAccountId(ACCT)).thenReturn(Optional.empty());

        assertEquals(1, service.reconcileFromUpwind());
        verify(connectionRepo).save(argThat(c ->
                ACCT.equals(c.getCloudAccountId())
                        && "CONNECTED".equals(c.getStatus())
                        && "cred-x".equals(c.getUpwindCredentialId())
                        && c.getConnectionVerifiedAt() != null));
    }

    /** Idempotent: a row already matching the registration must not be rewritten every 2 minutes. */
    @Test
    void reconcileLeavesAlreadyCurrentRowsAlone() {
        when(integrationClient.isConfigured()).thenReturn(true);
        when(integrationClient.listCloudCredentials()).thenReturn(List.of(
                new UpwindIntegrationClient.CloudCredential(
                        "cred-x", "n", "connected", ACCT, true, true)));
        UpwindAccountConnection current = connected("cred-x");
        when(connectionRepo.findByCloudAccountId(ACCT)).thenReturn(Optional.of(current));

        assertEquals(0, service.reconcileFromUpwind());
        verify(connectionRepo, never()).save(any());
    }

    /** A registration Upwind reports as not connected must not be adopted as connected. */
    @Test
    void reconcileIgnoresRegistrationsThatAreNotConnected() {
        when(integrationClient.isConfigured()).thenReturn(true);
        when(integrationClient.listCloudCredentials()).thenReturn(List.of(
                new UpwindIntegrationClient.CloudCredential(
                        "cred-x", "n", "pending", ACCT, true, true)));

        assertEquals(0, service.reconcileFromUpwind());
        verify(connectionRepo, never()).save(any());
    }

    /** A failed list must change nothing rather than clearing local state. */
    @Test
    void reconcileIsANoOpWhenUpwindCannotBeRead() {
        when(integrationClient.isConfigured()).thenReturn(true);
        when(integrationClient.listCloudCredentials())
                .thenThrow(new IllegalStateException("upstream down"));

        assertEquals(0, service.reconcileFromUpwind());
        verify(connectionRepo, never()).save(any());
    }

    // ── Authoritative status ──

    /** Registration is authoritative and available before the first scan, so it wins over assets. */
    @Test
    void prefersUpwindsRegistrationOverInferringFromAssets() {
        when(integrationClient.isConfigured()).thenReturn(true);
        when(integrationClient.findByCloudAccountId(ACCT)).thenReturn(Optional.of(
                new UpwindIntegrationClient.CloudCredential("c", "n", "connected", ACCT, true, true)));

        assertTrue(service.isLiveInUpwind(ACCT));
        // No need to ask the data API at all once Upwind says it is connected.
        verify(apiClient, never()).searchInventory(any(), anyInt());
    }

    /** Deployments predating self-service have no integration credential; assets remain the
     *  fallback so their existing connections still read as connected. */
    @Test
    void fallsBackToAssetsWhenTheIntegrationClientIsUnconfigured() throws Exception {
        when(integrationClient.isConfigured()).thenReturn(false);
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.searchInventory(any(), anyInt())).thenReturn(
                List.of(new ObjectMapper().readTree("{\"id\":\"asset-1\"}")));

        assertTrue(service.isLiveInUpwind(ACCT));
    }

    /** "Registered but the scanner is off" produces no findings and must not be reported as
     *  healthy — it is invisible otherwise. */
    @Test
    void callsOutARegisteredAccountWithTheScannerDisabled() {
        ownsAws();
        when(integrationClient.isConfigured()).thenReturn(true);
        when(integrationClient.findByCloudAccountId(ACCT)).thenReturn(Optional.of(
                new UpwindIntegrationClient.CloudCredential("c", "n", "connected", ACCT, false, true)));
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.searchInventory(any(), anyInt())).thenReturn(List.of());
        when(connectionRepo.findByCloudAccountId(ACCT)).thenReturn(Optional.empty());

        Map<String, Object> out = service.checkConnection(CLIENT, ACCT);

        assertEquals(false, out.get("scannerEnabled"));
        assertTrue(((String) out.get("message")).contains("scanner is disabled"),
                out.get("message").toString());
    }
}
