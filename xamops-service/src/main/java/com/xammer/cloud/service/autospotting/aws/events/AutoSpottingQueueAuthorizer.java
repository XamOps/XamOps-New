package com.xammer.cloud.service.autospotting.aws.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.config.SchedulingEnvironmentConfig;
import com.xammer.cloud.config.multitenancy.TenantContext;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.event.CloudAccountConnectedEvent;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.autospotting.core.JobRunner;
import com.xammer.cloud.service.autospotting.core.lease.LeaseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the AutoSpotting events queue's access policy in step with the connected AWS accounts (design-aws.md
 * §9.5, plan-phase-22.md task 22.3). A copy of {@code InventoryQueueAuthorizer} for the new queue, with the
 * differences the design asks for:
 *
 * <ul>
 *   <li>It runs under the job lease {@value #JOB} ({@link JobRunner}), so one instance writes the policy, not
 *       every instance.</li>
 *   <li>It rebuilds the whole policy from CONNECTED accounts with a valid account id: on account connect, at
 *       startup, and hourly. A disconnected account drops out on the next sync (AC-EVT-05).</li>
 *   <li>An empty list leaves the policy unchanged.</li>
 *   <li>Near the SQS size limit it switches from the {@code aws:PrincipalAccount} list to one
 *       {@code aws:PrincipalArn} pattern on the forwarder role name. That is safe only because
 *       {@link EventSenderVerifier} checks the sender of every message.</li>
 *   <li>It does nothing when the pipeline is off or {@link AwsEventProperties#refusal()} names a reason, so a
 *       non-prod profile can never rewrite the production queue's policy.</li>
 * </ul>
 * The queue stack deliberately creates no policy: two writers would fight. V-AWS-05: record the live policy
 * before cutover.
 */
@Service
public class AutoSpottingQueueAuthorizer {

    private static final Logger log = LoggerFactory.getLogger(AutoSpottingQueueAuthorizer.class);

    /** The job lease name (design-core.md §3.2). */
    public static final String JOB = "queue-policy-sync";
    static final String SID_CUSTOMER_SEND = "XamOpsAutoSpottingCustomerSend";
    static final String SID_BACKEND_CONSUME = "XamOpsAutoSpottingBackendConsume";
    /** SQS allows 8,192 bytes of policy; switch to the role pattern well before it (about 15 bytes per account). */
    static final int PATTERN_SWITCH_BYTES = 6_500;

    private final SqsClient sqs;
    private final CloudAccountRepository accounts;
    private final SchedulingEnvironmentConfig environment;
    private final ObjectMapper mapper;
    private final AwsEventProperties properties;
    private final JobRunner jobRunner;
    private final LeaseProperties leaseProperties;
    private final String backendPrincipalArn;

    public AutoSpottingQueueAuthorizer(SqsClient sqs, CloudAccountRepository accounts,
                                       SchedulingEnvironmentConfig environment, @Qualifier("objectMapper") ObjectMapper mapper,
                                       AwsEventProperties properties, JobRunner jobRunner,
                                       LeaseProperties leaseProperties,
                                       @Value("${autospotting.aws.events.backend-principal-arn:}")
                                       String backendPrincipalArn) {
        this.sqs = sqs;
        this.accounts = accounts;
        this.environment = environment;
        this.mapper = mapper;
        this.properties = properties;
        this.jobRunner = jobRunner;
        this.leaseProperties = leaseProperties;
        this.backendPrincipalArn = backendPrincipalArn;
    }

    // ── triggers ─────────────────────────────────────────────────────────────

    /** An account that just connected can deliver events without anyone touching infrastructure. */
    @Async("backgroundTaskExecutor")
    @EventListener
    public void onAccountConnected(CloudAccountConnectedEvent event) {
        if (!"AWS".equals(event.provider())) {
            return;
        }
        requestSync();
    }

    /** Catches accounts added while the app was down. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        requestSync();
    }

    /** Hourly backstop; also corrects a manual edit of the policy. */
    @Scheduled(scheduler = "spotTaskScheduler", fixedDelayString = "${autospotting.aws.events.auth-sync-interval-ms:3600000}",
            initialDelayString = "${autospotting.aws.events.auth-sync-initial-delay-ms:300000}")
    public void scheduledSync() {
        jobRunner.runLeased(JOB, null, leaseProperties.reconcilerTtl(), leaseProperties.reconcilerHeartbeat(),
                Duration.ZERO, this::syncQueuePolicy);
    }

    private void requestSync() {
        jobRunner.runLeased(JOB, null, leaseProperties.reconcilerTtl(), leaseProperties.reconcilerHeartbeat(),
                Duration.ZERO, this::syncQueuePolicy);
    }

    // ── policy rebuild ───────────────────────────────────────────────────────

    /** Rebuild the policy from the connected accounts. Never throws: a failure must not break onboarding or startup. */
    public void syncQueuePolicy() {
        if (!properties.enabled() || properties.refusal().isPresent()) {
            return;
        }
        String queueUrl = properties.queueUrl();
        try {
            Set<String> accountIds = connectedAwsAccountIds();
            if (accountIds.isEmpty()) {
                // An empty condition list would match nothing, indistinguishable from a broken deploy.
                log.info("[AutoSpottingAuth] No connected AWS accounts: leaving the queue policy unchanged");
                return;
            }
            String queueArn = queueArn(queueUrl);
            if (queueArn == null) {
                return;
            }
            String desired = buildPolicy(queueArn, accountIds);
            if (desired.length() > PATTERN_SWITCH_BYTES) {
                desired = buildPatternPolicy(queueArn);
            }
            String current = currentPolicy(queueUrl);
            if (current != null && signature(current).equals(signature(desired))) {
                log.debug("[AutoSpottingAuth] Queue policy already current for {} account(s)", accountIds.size());
                return;
            }
            sqs.setQueueAttributes(SetQueueAttributesRequest.builder().queueUrl(queueUrl)
                    .attributes(Map.of(QueueAttributeName.POLICY, desired)).build());
            log.info("[AutoSpottingAuth] Queue policy updated: {} connected account(s)", accountIds.size());
        } catch (Exception e) {
            log.error("[AutoSpottingAuth] Could not sync the queue policy ({}). New accounts cannot deliver events "
                    + "until this is fixed; check sqs:SetQueueAttributes on the events queue.", e.getMessage());
        }
    }

    /** Every CONNECTED AWS account with a 12-digit id. */
    Set<String> connectedAwsAccountIds() {
        Set<String> ids = new LinkedHashSet<>();
        TenantContext.setCurrentTenant(environment.getCurrentTenantId());
        try {
            for (CloudAccount account : accounts.findAll()) {
                if ("AWS".equals(account.getProvider()) && "CONNECTED".equals(account.getStatus())) {
                    String awsId = account.getAwsAccountId();
                    if (awsId != null && awsId.matches("\\d{12}")) {
                        ids.add(awsId);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[AutoSpottingAuth] Could not read accounts: {}", e.getMessage());
        } finally {
            TenantContext.clear();
        }
        return ids;
    }

    /** The account-list policy: {@code Principal "*"} narrowed by {@code aws:PrincipalAccount}. */
    String buildPolicy(String queueArn, Set<String> accountIds) throws Exception {
        ObjectNode policy = basePolicy();
        ObjectNode send = sendStatement(policy, queueArn);
        ArrayNode allowed = send.putObject("Condition").putObject("StringEquals").putArray("aws:PrincipalAccount");
        accountIds.forEach(allowed::add);
        addConsume(policy, queueArn);
        return mapper.writeValueAsString(policy);
    }

    /** The pattern policy near the size limit: the forwarder role name in any account. */
    String buildPatternPolicy(String queueArn) throws Exception {
        ObjectNode policy = basePolicy();
        ObjectNode send = sendStatement(policy, queueArn);
        String suffix = properties.suffix().orElse("");
        send.putObject("Condition").putObject("ArnLike").put("aws:PrincipalArn",
                "arn:aws:iam::*:role/" + AwsForwarderRoleLookup.ROLE_PREFIX + suffix);
        addConsume(policy, queueArn);
        return mapper.writeValueAsString(policy);
    }

    private ObjectNode basePolicy() {
        ObjectNode policy = mapper.createObjectNode();
        policy.put("Version", "2012-10-17");
        policy.putArray("Statement");
        return policy;
    }

    private ObjectNode sendStatement(ObjectNode policy, String queueArn) {
        ObjectNode send = ((ArrayNode) policy.get("Statement")).addObject();
        send.put("Sid", SID_CUSTOMER_SEND);
        send.put("Effect", "Allow");
        send.putObject("Principal").put("AWS", "*");
        send.putArray("Action").add("sqs:SendMessage").add("sqs:GetQueueUrl");
        send.put("Resource", queueArn);
        return send;
    }

    private void addConsume(ObjectNode policy, String queueArn) {
        if (backendPrincipalArn == null || backendPrincipalArn.isBlank()) {
            return;
        }
        ObjectNode consume = ((ArrayNode) policy.get("Statement")).addObject();
        consume.put("Sid", SID_BACKEND_CONSUME);
        consume.put("Effect", "Allow");
        consume.putObject("Principal").put("AWS", backendPrincipalArn);
        consume.putArray("Action").add("sqs:ReceiveMessage").add("sqs:DeleteMessage")
                .add("sqs:GetQueueAttributes").add("sqs:SetQueueAttributes").add("sqs:GetQueueUrl");
        consume.put("Resource", queueArn);
    }

    private String queueArn(String queueUrl) {
        try {
            String arn = sqs.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(queueUrl)
                    .attributeNames(QueueAttributeName.QUEUE_ARN).build()).attributes().get(QueueAttributeName.QUEUE_ARN);
            if (arn == null || arn.isBlank()) {
                log.warn("[AutoSpottingAuth] Queue ARN unavailable for {}", queueUrl);
                return null;
            }
            return arn;
        } catch (Exception e) {
            log.warn("[AutoSpottingAuth] Could not read queue attributes: {}", e.getMessage());
            return null;
        }
    }

    private String currentPolicy(String queueUrl) {
        try {
            return sqs.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(queueUrl)
                    .attributeNames(QueueAttributeName.POLICY).build()).attributes().get(QueueAttributeName.POLICY);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * What the policy authorises, for comparison: the account set, or the role pattern. SQS reorders and
     * normalises documents, so comparing the raw JSON would rewrite the policy on every sync.
     */
    String signature(String policyJson) {
        try {
            JsonNode statements = mapper.readTree(policyJson).path("Statement");
            Set<String> out = new java.util.TreeSet<>();
            if (statements.isArray()) {
                for (JsonNode st : statements) {
                    if (!SID_CUSTOMER_SEND.equals(st.path("Sid").asText())) {
                        continue;
                    }
                    JsonNode accountsNode = st.path("Condition").path("StringEquals").path("aws:PrincipalAccount");
                    if (accountsNode.isArray()) {
                        accountsNode.forEach(n -> out.add("acct:" + n.asText()));
                    } else if (accountsNode.isTextual()) {
                        out.add("acct:" + accountsNode.asText());
                    }
                    JsonNode pattern = st.path("Condition").path("ArnLike").path("aws:PrincipalArn");
                    if (pattern.isTextual()) {
                        out.add("arn:" + pattern.asText());
                    }
                }
            }
            return out.toString();
        } catch (Exception e) {
            return "unreadable";
        }
    }

    /** The accounts the live policy authorises (the status view); empty when the pattern policy is in force. */
    public Set<String> authorizedAccounts() {
        String policy = currentPolicy(properties.queueUrl());
        Set<String> out = new LinkedHashSet<>();
        if (policy == null) {
            return out;
        }
        for (String s : signature(policy).replaceAll("[\\[\\]]", "").split(",\\s*")) {
            if (s.startsWith("acct:")) {
                out.add(s.substring(5));
            }
        }
        return out;
    }
}
