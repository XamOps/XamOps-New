package com.xammer.cloud.service.upwind;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.UpwindAccountConnection;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.UpwindAccountConnectionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Self-service onboarding of a customer AWS account into Xammer's single Upwind organization.
 *
 * <h2>Why this can exist at all</h2>
 * Upwind exposes no usable cloud-account API: every {@code /cloud-accounts} and
 * {@code /byoc-credentials} path 404s or 401s on {@code api.upwind.io}, and account registration
 * actually lives on a different service ({@code integration.upwind.io}) that our API-type
 * credential gets a token for but with zero permissions. Probed 2026-08-19.
 *
 * <p>None of that matters, because Upwind's own CloudFormation template does the work in-account:
 * <ul>
 *   <li>{@code Custom::UpwindGeneratedToken} calls Upwind and returns a freshly generated
 *       {@code ExternalId} — so the external ID is NOT something a Xammer admin pre-mints in the
 *       Upwind console. It is unique per stack, by construction.</li>
 *   <li>{@code Custom::UpwindRegisteredAccount} posts that external ID plus the created role ARN
 *       back to Upwind, registering the account.</li>
 * </ul>
 * So one shared "Cloud account" credential serves every customer: per-account uniqueness comes
 * from the generated external ID, not from the credential. That is what makes this fully
 * self-service with no Upwind console visit per customer.
 *
 * <h2>The credential is ours, never the customer's to enter</h2>
 * The client id/secret are injected into the launch URL server-side. They are valid against
 * Xammer's whole Upwind organization, so a UI that asked the customer to type them would be
 * handing every customer a credential for every other customer's onboarding. They are also
 * needed on stack UPDATE and DELETE — the custom resources run on every lifecycle event — so
 * they cannot be rotated away once customers hold stacks.
 */
@Service
@Slf4j
public class UpwindOnboardingService {

    /**
     * Upwind's published onboarding template.
     *
     * Pinned as a property so a customer onboarding tomorrow can be given the same template we
     * tested. {@code latest.yaml} is a moving target: Upwind can change its parameters without
     * notice, and the parameter names below would silently stop matching.
     */
    @Value("${upwind.onboarding.template-url:https://s3.amazonaws.com/cfn.upwind.io/onboarding/latest.yaml}")
    private String templateUrl;

    /** Upwind's own AWS account, the trusted principal in the role's trust policy. */
    @Value("${upwind.onboarding.trusted-account:340457201789}")
    private String trustedAccount;

    /** Upwind region: us, eu or me. Must match the API base the sync talks to. */
    @Value("${upwind.onboarding.region:us}")
    private String upwindRegion;

    /**
     * Region the stack is deployed into.
     *
     * Upwind's template sources its onboarding Lambda from
     * {@code upwind-serverless-functions-${AWS::Region}}, so this cannot be left to the
     * customer's current console region — an unsupported region fails the stack with an opaque
     * "bucket does not exist".
     */
    @Value("${upwind.onboarding.stack-region:us-east-1}")
    private String stackRegion;

    /** Cloud-account-type client id. NOT the API sync credential. */
    @Value("${upwind.onboarding.client-id:}")
    private String onboardingClientId;

    @Value("${upwind.onboarding.client-secret:}")
    private String onboardingClientSecret;

    @Value("${upwind.org-id:}")
    private String orgId;

    private final CloudAccountRepository cloudAccountRepository;
    private final UpwindAccountConnectionRepository connectionRepo;
    private final UpwindApiClient apiClient;
    private final UpwindIntegrationClient integrationClient;
    private final com.xammer.cloud.repository.ClientRepository clientRepository;

    public UpwindOnboardingService(CloudAccountRepository cloudAccountRepository,
                                   UpwindAccountConnectionRepository connectionRepo,
                                   UpwindApiClient apiClient,
                                   UpwindIntegrationClient integrationClient,
                                   com.xammer.cloud.repository.ClientRepository clientRepository) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.connectionRepo = connectionRepo;
        this.apiClient = apiClient;
        this.integrationClient = integrationClient;
        this.clientRepository = clientRepository;
    }

    /**
     * Whether this client is permitted to run self-service onboarding at all.
     *
     * Absent client row reads as allowed, matching the column default: a lookup failure must not
     * become a silent feature withdrawal.
     */
    public boolean isAllowedForClient(Long clientId) {
        return clientRepository.findById(clientId)
                .map(com.xammer.cloud.domain.Client::isXamSecureOnboardingEnabled)
                .orElse(true);
    }

    private void requireAllowed(Long clientId) {
        if (!isAllowedForClient(clientId)) {
            throw new AccessDeniedException(
                    "Self-service security onboarding is not enabled for this account. "
                    + "Contact your XamOps administrator.");
        }
    }

    /** Whether a launch URL can be produced at all, i.e. whether the shared credential is set. */
    public boolean isConfigured() {
        return notBlank(onboardingClientId) && notBlank(onboardingClientSecret) && notBlank(orgId);
    }

    /**
     * Builds the CloudFormation quick-create URL for one of the caller's own AWS accounts and
     * marks the connection PENDING.
     *
     * <p><b>The returned URL contains the shared client secret in its query string.</b> It must be
     * treated as a credential: handed straight to the authenticated customer over TLS, never
     * logged, never persisted, never emailed. Only the account id and stack name are stored.
     *
     * @throws AccessDeniedException if the account does not belong to this client — the whole
     *         point of resolving through the client's own accounts rather than trusting the id
     *         off the request.
     */
    @Transactional
    public Map<String, Object> createLaunchUrl(Long clientId, String cloudAccountId) {
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "Upwind onboarding is not configured: set upwind.onboarding.client-id and "
                    + "upwind.onboarding.client-secret (type 'Cloud account' credentials from the "
                    + "Upwind console — not the API sync credential).");
        }
        requireAllowed(clientId);
        CloudAccount account = ownedAccount(clientId, cloudAccountId);
        if (!"AWS".equalsIgnoreCase(account.getProvider())) {
            throw new IllegalArgumentException(
                    "Self-service onboarding currently covers AWS only; this account is "
                    + account.getProvider() + ". GCP and Azure use different Upwind flows.");
        }

        String stackName = stackNameFor(cloudAccountId);
        UpwindAccountConnection conn = connectionRepo.findByCloudAccountId(cloudAccountId)
                .orElseGet(UpwindAccountConnection::new);
        conn.setCloudAccountId(cloudAccountId);
        // Never downgrade a working connection: re-running onboarding on a CONNECTED account
        // (to pick up new Upwind permissions via a stack update) must not blank the tab out.
        if (!"CONNECTED".equals(conn.getStatus())) {
            conn.setStatus("PENDING");
        }
        conn.setStackRequestedAt(Instant.now());
        conn.setStackRegion(stackRegion);
        conn.setStackName(stackName);
        conn.setUpdatedAt(Instant.now());
        connectionRepo.save(conn);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("launchUrl", quickCreateUrl(stackName));
        out.put("stackName", stackName);
        out.put("stackRegion", stackRegion);
        out.put("cloudAccountId", cloudAccountId);
        out.put("status", conn.getStatus());
        return out;
    }

    /**
     * Upwind's console names these {@code upwind-readonly-permissions-<epoch>}. We use the account
     * id instead of a timestamp so re-running onboarding UPDATES the existing stack rather than
     * creating a second one — two stacks in one account would each try to register it and fight
     * over the role name.
     */
    private String stackNameFor(String cloudAccountId) {
        return "upwind-readonly-permissions-" + cloudAccountId;
    }

    private String quickCreateUrl(String stackName) {
        // KmsKeyArn / CredentialsSecretArn are deliberately left at their NOT_SET defaults: the
        // template's own Rules assertion accepts EITHER ClientId+ClientSecret OR
        // CredentialsSecretArn+KmsKeyArn, and the secret-ARN path additionally needs a
        // customer-managed KMS key and a secret that already exists in the customer's account.
        // Passing the credentials directly lets the template create the secret itself.
        Map<String, String> params = new LinkedHashMap<>();
        params.put("stackName", stackName);
        params.put("param_OrganizationId", orgId);
        params.put("param_TrustedAccount", trustedAccount);
        params.put("param_UpwindRegion", upwindRegion);
        params.put("param_ClientId", onboardingClientId);
        params.put("param_ClientSecret", onboardingClientSecret);

        StringBuilder q = new StringBuilder();
        q.append("templateURL=").append(enc(templateUrl));
        params.forEach((k, v) -> q.append('&').append(k).append('=').append(enc(v)));
        return "https://" + stackRegion + ".console.aws.amazon.com/cloudformation/home?region="
                + enc(stackRegion) + "#/stacks/quickcreate?" + q;
    }

    /**
     * Every Upwind registration, keyed by AWS account id, for callers that need several at once.
     *
     * Empty map on failure or when the integration credential is unset — callers must treat that
     * as "no information", not as "nothing is connected".
     */
    public Map<String, UpwindIntegrationClient.CloudCredential> registrationsByAccountId() {
        if (!integrationClient.isConfigured()) return Map.of();
        try {
            Map<String, UpwindIntegrationClient.CloudCredential> out = new LinkedHashMap<>();
            for (UpwindIntegrationClient.CloudCredential c : integrationClient.listCloudCredentials()) {
                if (c.cloudAccountId() != null) out.put(c.cloudAccountId(), c);
            }
            return out;
        } catch (Exception e) {
            log.warn("[Upwind] could not list registrations: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * Upwind's own registration record for an account, when it has one.
     *
     * This is the authoritative connection state and is preferred over
     * {@link #reportsAssets(String)}: it distinguishes "registered but the scanner is off" from
     * "never registered", and it reports a freshly registered account as connected immediately
     * instead of waiting for the first scan to produce assets.
     */
    public Optional<UpwindIntegrationClient.CloudCredential> registrationFor(String cloudAccountId) {
        if (!integrationClient.isConfigured()) return Optional.empty();
        try {
            return integrationClient.findByCloudAccountId(cloudAccountId);
        } catch (Exception e) {
            // Unknown, not absent. Treating an API failure as "not registered" would let the
            // verifier and the disconnect path both act on a false negative.
            log.warn("[Upwind] could not read registration for account {}: {}",
                    cloudAccountId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Fallback signal: does Upwind report any asset for the account?
     *
     * Retained because it proves data is actually flowing, which a registration record does not —
     * an account can be registered while the role is broken. Used to corroborate, not to decide.
     *
     * <p>{@code cloud_account_id} must be the ONLY condition. Pairing it with
     * {@code cloud_provider} returns HTTP 200 and an empty {@code items[]}, which would read as
     * "not connected" for every account forever (the same trap documented on
     * {@link UpwindSyncService#syncInventory}).
     */
    public boolean reportsAssets(String cloudAccountId) {
        if (!apiClient.isConfigured()) return false;
        try {
            List<JsonNode> assets = apiClient.searchInventory(
                    List.of(Map.of("field", "cloud_account_id", "operator", "eq",
                            "value", List.of(cloudAccountId))), 1);
            return !assets.isEmpty();
        } catch (Exception e) {
            log.warn("[Upwind] could not verify assets for account {}: {}",
                    cloudAccountId, e.getMessage());
            return false;
        }
    }

    /**
     * True when Upwind considers the account connected, by either signal.
     *
     * Registration is checked first because it is authoritative and available sooner; assets are
     * the fallback for the case where the integration credential is unset but the data API is
     * working, which is how deployments that predate self-service onboarding look.
     */
    public boolean isLiveInUpwind(String cloudAccountId) {
        return registrationFor(cloudAccountId).map(UpwindIntegrationClient.CloudCredential::connected)
                .orElseGet(() -> reportsAssets(cloudAccountId));
    }

    /**
     * Disconnects one account from XamSecure.
     *
     * Order is deliberate: deregister in Upwind FIRST, then tell the customer to delete their
     * stack. The reverse leaves a window where Upwind is scanning against a role that has just
     * been deleted, which surfaces to them as authorization failures rather than as a clean
     * removal.
     *
     * <p>Deleting the stack alone is NOT a disconnect. Upwind's template Delete handler replies
     * SUCCESS to CloudFormation and calls no API — it even logs "Integration deleted
     * successfully" while doing nothing — so the registration survives and the org accumulates a
     * connection that can never succeed again.
     *
     * <p>Local findings are left in place rather than purged. They are historical fact and every
     * query is account-scoped, so they stay invisible to anyone who no longer owns the account;
     * deleting a customer's security history on a disconnect is not recoverable, whereas leaving
     * it is.
     */
    @Transactional
    public Map<String, Object> disconnect(Long clientId, String cloudAccountId) {
        ownedAccount(clientId, cloudAccountId);

        UpwindAccountConnection conn = connectionRepo.findByCloudAccountId(cloudAccountId)
                .orElse(null);

        // Resolve the id from Upwind rather than trusting a stored one: connections made before
        // this feature existed (Onfinance, and anything onboarded through the console) have no
        // upwind_credential_id, and those are precisely the ones most likely to need removing.
        String credentialId = conn == null ? null : conn.getUpwindCredentialId();
        if (credentialId == null) {
            credentialId = registrationFor(cloudAccountId)
                    .map(UpwindIntegrationClient.CloudCredential::id).orElse(null);
        }

        boolean deregistered = false;
        String detail;
        if (credentialId == null) {
            // Nothing registered upstream. Still worth clearing local state so the UI stops
            // claiming the account is protected.
            detail = "No active registration found in XamSecure for this account.";
        } else if (!integrationClient.isConfigured()) {
            throw new IllegalStateException(
                    "Cannot disconnect: upwind.onboarding.client-id/secret are not configured, "
                    + "so the registration cannot be removed upstream.");
        } else {
            deregistered = integrationClient.deleteCloudCredential(credentialId);
            detail = deregistered
                    ? "Removed from XamSecure. Now delete the CloudFormation stack in your AWS "
                      + "account to remove the IAM role and stored credential."
                    : "Could not remove the registration upstream; nothing was changed.";
        }

        if (deregistered || credentialId == null) {
            if (conn != null) {
                conn.setStatus("NOT_CONNECTED");
                conn.setUpwindCredentialId(null);
                conn.setConnectionVerifiedAt(null);
                conn.setStackRequestedAt(null);
                conn.setDisconnectedAt(Instant.now());
                conn.setUpdatedAt(Instant.now());
                connectionRepo.save(conn);
            }
            log.info("[Upwind] account {} disconnected from XamSecure (credentialId={})",
                    cloudAccountId, credentialId);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cloudAccountId", cloudAccountId);
        out.put("status", conn == null ? "NOT_CONNECTED" : conn.getStatus());
        out.put("deregistered", deregistered);
        out.put("stackName", conn == null ? null : conn.getStackName());
        out.put("stackRegion", conn == null ? null : conn.getStackRegion());
        out.put("message", detail);
        return out;
    }

    /**
     * Promotes every PENDING connection that Upwind has started reporting assets for.
     *
     * Only ever moves PENDING to CONNECTED. It cannot demote: Upwind legitimately reports nothing
     * for an account with no scannable resources yet, and a verifier that treated silence as
     * disconnection would flap.
     *
     * @return how many connections were promoted
     */
    /**
     * Adopts every account Upwind reports as connected, creating the local row if absent.
     *
     * Without this, an account connected OUTSIDE XamOps is invisible: {@link
     * #verifyPendingConnections} reads {@code findByStatusNot("CONNECTED")}, which only returns
     * rows that already exist, and {@link XamSecureService#getOnboardingStatus} maps a missing row
     * to NOT_CONNECTED. So every account onboarded through the Upwind console before this feature
     * existed — Xammer's own included — displayed as "Not protected" for ever, with no way to
     * correct it: the UI only offers "Check status" for PENDING rows, so a NOT_CONNECTED account
     * had no button that would re-check it.
     *
     * Upwind's registration list is the authoritative source and is keyed on the AWS account id,
     * so it needs nothing from us to match. Reconciling from it also removes the assumption that
     * XamOps is the only thing that ever creates connections.
     *
     * @return how many rows were created or promoted
     */
    @Transactional
    public int reconcileFromUpwind() {
        if (!integrationClient.isConfigured()) return 0;
        List<UpwindIntegrationClient.CloudCredential> registrations;
        try {
            registrations = integrationClient.listCloudCredentials();
        } catch (Exception e) {
            log.warn("[Upwind] could not list registrations to reconcile: {}", e.getMessage());
            return 0;
        }

        int changed = 0;
        for (UpwindIntegrationClient.CloudCredential reg : registrations) {
            if (reg.cloudAccountId() == null || reg.cloudAccountId().isBlank()) continue;
            if (!reg.connected()) continue;

            UpwindAccountConnection conn = connectionRepo.findByCloudAccountId(reg.cloudAccountId())
                    .orElseGet(() -> {
                        UpwindAccountConnection n = new UpwindAccountConnection();
                        n.setCloudAccountId(reg.cloudAccountId());
                        return n;
                    });
            boolean alreadyCurrent = "CONNECTED".equals(conn.getStatus())
                    && reg.id().equals(conn.getUpwindCredentialId());
            if (alreadyCurrent) continue;

            conn.setStatus("CONNECTED");
            conn.setUpwindCredentialId(reg.id());
            if (conn.getConnectionVerifiedAt() == null) conn.setConnectionVerifiedAt(Instant.now());
            // Upwind still holding a registration means the account IS connected, whatever we
            // recorded earlier — either a disconnect's DELETE failed, or it was re-onboarded.
            // Unlike the asset-based signal, this one cannot lag behind a successful disconnect,
            // because a successful disconnect removes the registration being read here.
            conn.setDisconnectedAt(null);
            conn.setUpdatedAt(Instant.now());
            connectionRepo.save(conn);
            changed++;
            log.info("[Upwind] adopted registration for account {} (credential {})",
                    reg.cloudAccountId(), reg.id());
        }
        return changed;
    }

    @Transactional
    public int verifyPendingConnections() {
        List<UpwindAccountConnection> pending = connectionRepo.findByStatusNot("CONNECTED");
        int promoted = 0;
        for (UpwindAccountConnection conn : pending) {
            if (conn.getCloudAccountId() == null || conn.getCloudAccountId().isBlank()) continue;
            // A connection deliberately torn down must not be resurrected by the poller. Upwind
            // can still be reporting assets for minutes afterwards, and re-promoting would undo a
            // disconnect the customer just performed.
            if (conn.getDisconnectedAt() != null) continue;
            Optional<UpwindIntegrationClient.CloudCredential> reg =
                    registrationFor(conn.getCloudAccountId());
            boolean live = reg.map(UpwindIntegrationClient.CloudCredential::connected)
                    .orElseGet(() -> reportsAssets(conn.getCloudAccountId()));
            if (!live) continue;
            conn.setStatus("CONNECTED");
            conn.setConnectionVerifiedAt(Instant.now());
            reg.ifPresent(c -> conn.setUpwindCredentialId(c.id()));
            conn.setUpdatedAt(Instant.now());
            connectionRepo.save(conn);
            promoted++;
            log.info("[Upwind] account {} is connected in Upwind — marked CONNECTED",
                    conn.getCloudAccountId());
        }
        return promoted;
    }

    /**
     * On-demand check for one account, so the UI's "Check connection" button does not have to
     * wait for the poller.
     */
    @Transactional
    public Map<String, Object> checkConnection(Long clientId, String cloudAccountId) {
        ownedAccount(clientId, cloudAccountId);
        Optional<UpwindIntegrationClient.CloudCredential> reg = registrationFor(cloudAccountId);
        boolean registered = reg.map(UpwindIntegrationClient.CloudCredential::connected).orElse(false);
        boolean assets = reportsAssets(cloudAccountId);
        boolean live = registered || assets;

        UpwindAccountConnection conn = connectionRepo.findByCloudAccountId(cloudAccountId)
                .orElseGet(() -> {
                    UpwindAccountConnection n = new UpwindAccountConnection();
                    n.setCloudAccountId(cloudAccountId);
                    n.setStatus("NOT_CONNECTED");
                    n.setUpdatedAt(Instant.now());
                    return n;
                });
        if (live && !"CONNECTED".equals(conn.getStatus())) {
            conn.setStatus("CONNECTED");
            conn.setConnectionVerifiedAt(Instant.now());
            conn.setDisconnectedAt(null);
            conn.setUpdatedAt(Instant.now());
        }
        reg.ifPresent(c -> conn.setUpwindCredentialId(c.id()));
        connectionRepo.save(conn);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cloudAccountId", cloudAccountId);
        out.put("status", conn.getStatus());
        out.put("reportingAssets", assets);
        out.put("registered", registered);
        // Surfaced separately because "registered but the scanner is off" produces no findings and
        // is otherwise indistinguishable from a broken connection.
        out.put("scannerEnabled", reg.map(UpwindIntegrationClient.CloudCredential::cloudScannerEnabled)
                .orElse(null));
        out.put("verifiedAt", conn.getConnectionVerifiedAt());
        out.put("message", message(registered, assets, reg, conn));
        return out;
    }

    private String message(boolean registered, boolean assets,
                           Optional<UpwindIntegrationClient.CloudCredential> reg,
                           UpwindAccountConnection conn) {
        if (registered && assets) return "Connected and reporting data for this account.";
        if (registered && reg.isPresent() && !reg.get().cloudScannerEnabled()) {
            return "Connected, but the cloud scanner is disabled for this account, so no findings "
                    + "will be produced. Contact your XamOps administrator.";
        }
        if (registered) return "Connected. The first scan is still in progress.";
        if (assets) return "Reporting data for this account.";
        return waitingMessage(conn);
    }

    /**
     * What to tell someone whose account is not reporting yet.
     *
     * A stack that deployed successfully registers the account immediately and assets follow
     * within minutes, so a long silence is a failed or abandoned stack rather than slow progress.
     * Saying "still scanning" indefinitely would send people to support instead of to their
     * CloudFormation console, where the actual error is.
     */
    private String waitingMessage(UpwindAccountConnection conn) {
        Instant requested = conn.getStackRequestedAt();
        if (requested == null) {
            return "This account has not been connected yet. Start with Connect account.";
        }
        long minutes = java.time.Duration.between(requested, Instant.now()).toMinutes();
        if (minutes < STACK_GRACE_MINUTES) {
            return "Waiting for the first scan. This usually takes a few minutes after the "
                    + "stack finishes.";
        }
        return "No data yet after " + minutes + " minutes. Check that the CloudFormation stack '"
                + conn.getStackName() + "' in " + conn.getStackRegion()
                + " reached CREATE_COMPLETE — a rolled-back stack leaves no connection behind.";
    }

    /** How long a fresh stack is given before we stop calling it "in progress". */
    static final long STACK_GRACE_MINUTES = 30;

    private CloudAccount ownedAccount(Long clientId, String cloudAccountId) {
        Optional<CloudAccount> found = cloudAccountRepository.findByClientId(clientId).stream()
                .filter(a -> cloudAccountId.equals(a.getProviderAccountId()))
                .findFirst();
        return found.orElseThrow(() -> new AccessDeniedException(
                "Cloud account " + cloudAccountId + " does not belong to this client"));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
