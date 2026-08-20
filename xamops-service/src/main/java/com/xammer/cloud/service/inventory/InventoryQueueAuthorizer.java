package com.xammer.cloud.service.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.config.SchedulingEnvironmentConfig;
import com.xammer.cloud.config.multitenancy.TenantContext;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.event.CloudAccountConnectedEvent;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.SetQueueAttributesRequest;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the inventory queue's access policy in step with the connected accounts.
 *
 * <p>
 * The queue is written to by Lambdas in customer accounts, so its policy has to name
 * which accounts may send. Maintaining that list by hand does not survive contact with
 * reality — at a hundred customers and growing, a CloudFormation parameter someone has to
 * remember to update is a parameter that will be out of date, and the symptom is silent:
 * the new customer's events are rejected, nothing in XamOps errors, and their inventory
 * quietly falls back to 60s polling.
 *
 * <p>
 * So the list is derived from the database instead. The policy is rebuilt from every
 * CONNECTED AWS account whenever an account connects, on startup, and on a slow timer as a
 * backstop. Onboarding a customer therefore authorises them automatically, and removing one
 * de-authorises them on the next rebuild.
 *
 * <p>
 * The alternative — an open queue — was rejected deliberately. An attacker who learned the
 * URL could inject fabricated resources into a real tenant's inventory. The reconciler
 * would sweep them within a minute, but "wrong data for a minute" is not a property worth
 * conceding for convenience that this class provides anyway.
 *
 * <p>
 * This service owns the policy outright; the CloudFormation stack deliberately does not
 * create one. Two writers would fight, and a stack update would silently revert every
 * customer authorisation.
 */
@Service
public class InventoryQueueAuthorizer {

    private static final Logger log = LoggerFactory.getLogger(InventoryQueueAuthorizer.class);

    private static final String SID_CUSTOMER_SEND = "XamOpsInventoryCustomerSend";
    private static final String SID_BACKEND_CONSUME = "XamOpsInventoryBackendConsume";

    private final SqsClient sqsClient;
    private final CloudAccountRepository cloudAccountRepository;
    private final SchedulingEnvironmentConfig envConfig;
    private final ObjectMapper objectMapper;

    @Value("${xamops.inventory.events.enabled:false}")
    private boolean eventsEnabled;

    @Value("${xamops.inventory.events.queue-url:}")
    private String queueUrl;

    /**
     * ARN of the role the backend runs as. Included in the policy so the queue stays
     * consumable; since this class writes the whole policy, omitting it would lock the
     * backend out of its own queue.
     */
    @Value("${xamops.inventory.events.backend-principal-arn:}")
    private String backendPrincipalArn;

    @Value("${xamops.inventory.events.auto-authorize:true}")
    private boolean autoAuthorize;

    public InventoryQueueAuthorizer(SqsClient sqsClient,
            CloudAccountRepository cloudAccountRepository,
            SchedulingEnvironmentConfig envConfig,
            ObjectMapper objectMapper) {
        this.sqsClient = sqsClient;
        this.cloudAccountRepository = cloudAccountRepository;
        this.envConfig = envConfig;
        this.objectMapper = objectMapper;
    }

    // ── Triggers ────────────────────────────────────────────────────────────

    /**
     * Authorises a customer the moment they connect, so the account that just finished
     * onboarding can deliver events without anyone touching infrastructure.
     */
    @Async("backgroundTaskExecutor")
    @EventListener
    public void onAccountConnected(CloudAccountConnectedEvent event) {
        if (!"AWS".equals(event.provider())) {
            return;
        }
        log.info("[InventoryAuth] Account connected (id={}) — refreshing queue policy",
                event.cloudAccountId());
        syncQueuePolicy();
    }

    /** Catches accounts added while the app was down. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        syncQueuePolicy();
    }

    /**
     * Backstop. Covers an account whose connect event was lost, and corrects manual
     * edits to the policy. Hourly is ample: the event path handles the normal case and
     * this only exists so drift cannot persist indefinitely.
     */
    @Scheduled(fixedDelayString = "${xamops.inventory.events.auth-sync-interval-ms:3600000}",
            initialDelayString = "${xamops.inventory.events.auth-sync-initial-delay-ms:300000}")
    public void scheduledSync() {
        syncQueuePolicy();
    }

    // ── Policy rebuild ──────────────────────────────────────────────────────

    /**
     * Rebuilds the queue policy from the current set of connected AWS accounts.
     *
     * <p>
     * Never throws. A failure here degrades new customers to 60s polling, which is the
     * behaviour they would have had anyway; it must not break onboarding or startup.
     */
    public void syncQueuePolicy() {
        if (!eventsEnabled || !autoAuthorize) {
            return;
        }
        if (queueUrl == null || queueUrl.isBlank()) {
            log.warn("[InventoryAuth] events enabled but queue-url is blank — skipping policy sync");
            return;
        }

        try {
            Set<String> accountIds = connectedAwsAccountIds();
            if (accountIds.isEmpty()) {
                // Writing an empty principal list would produce a policy matching nothing,
                // which is indistinguishable from a broken deploy. Leaving the existing
                // policy alone is safer than replacing it with one that denies everyone.
                log.info("[InventoryAuth] No connected AWS accounts — leaving queue policy unchanged");
                return;
            }

            String queueArn = queueArn();
            if (queueArn == null) {
                return;
            }

            String desired = buildPolicy(queueArn, accountIds);
            String current = currentPolicy();

            if (current != null && policiesEquivalent(current, desired)) {
                log.debug("[InventoryAuth] Queue policy already covers {} account(s)", accountIds.size());
                return;
            }

            sqsClient.setQueueAttributes(SetQueueAttributesRequest.builder()
                    .queueUrl(queueUrl)
                    .attributes(Map.of(QueueAttributeName.POLICY, desired))
                    .build());

            log.info("[InventoryAuth] Queue policy updated — {} customer account(s) authorised to send",
                    accountIds.size());

        } catch (Exception e) {
            // Most likely cause is the backend role lacking sqs:SetQueueAttributes on the
            // queue. Named explicitly because the alternative symptom — customers silently
            // stuck on polling — is very hard to trace back to an IAM gap.
            log.error("[InventoryAuth] Could not sync queue policy ({}). New customers will fall "
                    + "back to 60s polling until this is resolved. Check that the backend role has "
                    + "sqs:SetQueueAttributes on the inventory queue.", e.getMessage());
        }
    }

    /**
     * Every AWS account currently connected, across tenants.
     *
     * <p>
     * Only CONNECTED accounts with a role ARN are included: a PENDING or FAILED account
     * has no working integration, so authorising it would widen the policy for no benefit.
     */
    private Set<String> connectedAwsAccountIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (String tenantId : tenants()) {
            TenantContext.setCurrentTenant(tenantId);
            try {
                for (CloudAccount account : cloudAccountRepository.findAll()) {
                    if (!"AWS".equals(account.getProvider())) {
                        continue;
                    }
                    if (!"CONNECTED".equals(account.getStatus())) {
                        continue;
                    }
                    String awsId = account.getAwsAccountId();
                    if (awsId != null && awsId.matches("\\d{12}")) {
                        ids.add(awsId);
                    }
                }
            } catch (Exception e) {
                log.warn("[InventoryAuth] Could not read accounts for tenant {}: {}",
                        tenantId, e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
        return ids;
    }

    private List<String> tenants() {
        return List.of(envConfig.getCurrentTenantId());
    }

    /**
     * Builds the two-statement policy.
     *
     * <p>
     * {@code Principal: "*"} narrowed by {@code aws:PrincipalAccount} rather than listing
     * role ARNs, because the sending principal is a Lambda execution role inside the
     * customer's account whose name we do not control. Restricting to the account is the
     * tightest condition available without dictating their role naming.
     */
    private String buildPolicy(String queueArn, Set<String> accountIds) throws Exception {
        ObjectNode policy = objectMapper.createObjectNode();
        policy.put("Version", "2012-10-17");
        ArrayNode statements = policy.putArray("Statement");

        ObjectNode send = statements.addObject();
        send.put("Sid", SID_CUSTOMER_SEND);
        send.put("Effect", "Allow");
        send.putObject("Principal").put("AWS", "*");
        ArrayNode sendActions = send.putArray("Action");
        sendActions.add("sqs:SendMessage");
        sendActions.add("sqs:GetQueueUrl");
        send.put("Resource", queueArn);
        ArrayNode allowed = send.putObject("Condition")
                .putObject("StringEquals")
                .putArray("aws:PrincipalAccount");
        accountIds.forEach(allowed::add);

        if (backendPrincipalArn != null && !backendPrincipalArn.isBlank()) {
            ObjectNode consume = statements.addObject();
            consume.put("Sid", SID_BACKEND_CONSUME);
            consume.put("Effect", "Allow");
            consume.putObject("Principal").put("AWS", backendPrincipalArn);
            ArrayNode consumeActions = consume.putArray("Action");
            consumeActions.add("sqs:ReceiveMessage");
            consumeActions.add("sqs:DeleteMessage");
            consumeActions.add("sqs:GetQueueAttributes");
            consumeActions.add("sqs:SetQueueAttributes");
            consumeActions.add("sqs:GetQueueUrl");
            consume.put("Resource", queueArn);
        }

        return objectMapper.writeValueAsString(policy);
    }

    private String queueArn() {
        try {
            Map<QueueAttributeName, String> attrs = sqsClient.getQueueAttributes(
                    GetQueueAttributesRequest.builder()
                            .queueUrl(queueUrl)
                            .attributeNames(QueueAttributeName.QUEUE_ARN)
                            .build())
                    .attributes();
            String arn = attrs.get(QueueAttributeName.QUEUE_ARN);
            if (arn == null || arn.isBlank()) {
                log.warn("[InventoryAuth] Queue ARN unavailable for {}", queueUrl);
                return null;
            }
            return arn;
        } catch (Exception e) {
            log.warn("[InventoryAuth] Could not read queue attributes: {}", e.getMessage());
            return null;
        }
    }

    private String currentPolicy() {
        try {
            return sqsClient.getQueueAttributes(GetQueueAttributesRequest.builder()
                    .queueUrl(queueUrl)
                    .attributeNames(QueueAttributeName.POLICY)
                    .build())
                    .attributes()
                    .get(QueueAttributeName.POLICY);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Compares the authorised account sets rather than the raw JSON.
     *
     * <p>
     * SQS normalises and reorders policy documents, so string comparison would report a
     * difference on every check and rewrite the policy on every sync — turning an idempotent
     * operation into constant churn.
     */
    private boolean policiesEquivalent(String currentJson, String desiredJson) {
        try {
            Set<String> a = accountsInPolicy(currentJson);
            Set<String> b = accountsInPolicy(desiredJson);
            return a.equals(b) && !a.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private Set<String> accountsInPolicy(String json) throws Exception {
        Set<String> out = new LinkedHashSet<>();
        var root = objectMapper.readTree(json);
        var statements = root.path("Statement");
        if (statements.isArray()) {
            for (var st : statements) {
                if (!SID_CUSTOMER_SEND.equals(st.path("Sid").asText())) {
                    continue;
                }
                var accounts = st.path("Condition").path("StringEquals").path("aws:PrincipalAccount");
                if (accounts.isArray()) {
                    accounts.forEach(n -> out.add(n.asText()));
                } else if (accounts.isTextual()) {
                    out.add(accounts.asText());
                }
            }
        }
        return out;
    }

    /** Exposed for the status endpoint so the authorised set is inspectable. */
    public Set<String> authorizedAccounts() {
        try {
            String policy = currentPolicy();
            return policy == null ? Set.of() : accountsInPolicy(policy);
        } catch (Exception e) {
            return Set.of();
        }
    }
}
