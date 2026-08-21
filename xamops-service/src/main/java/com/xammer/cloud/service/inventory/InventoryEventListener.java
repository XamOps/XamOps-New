package com.xammer.cloud.service.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.config.multitenancy.TenantContext;
import com.xammer.cloud.config.SchedulingEnvironmentConfig;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.service.security.SecurityFindingIngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Consumes resource change events forwarded from customer accounts.
 *
 * <p>
 * Deliberately built on the same long-poll shape as {@code SpotEventListener}, which has
 * been running this pattern against a customer-fed FIFO queue in production — same
 * envelope parsing, same {@code findByProviderAccountId} tenant resolution, same
 * delete-after-success discipline. Reusing a proven pattern beats introducing a second
 * concurrency model for the same problem.
 *
 * <p>
 * A batch is processed then acknowledged as a unit, and cache invalidation is deferred to
 * the end of the batch. That matters under churn: an autoscaling event storm can deliver
 * dozens of messages for one account, and evicting per message would rebuild the same
 * caches dozens of times.
 */
@Service
public class InventoryEventListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventListener.class);

    /** Same topic the reconciler uses, so the frontend needs one subscription. */
    private static final String TOPIC = "/topic/inventory-refresh";

    private final SqsClient sqsClient;
    private final CloudAccountRepository cloudAccountRepository;
    private final InventoryEventIngestService ingestService;
    private final RedisCacheService redisCache;
    private final InventoryChangeNotifier notifier;
    private final SchedulingEnvironmentConfig envConfig;
    private final ObjectMapper objectMapper;
    private final SecurityFindingIngestService securityIngest;

    @Value("${xamops.inventory.events.enabled:false}")
    private boolean enabled;

    @Value("${xamops.inventory.events.queue-url:}")
    private String queueUrl;

    @Value("${spring.profiles.active:local}")
    private String activeProfile;

    /**
     * Warns when a non-production environment is about to consume the shared queue.
     *
     * <p>
     * This is the one misconfiguration in the event pipeline that causes silent data loss
     * rather than an error. SQS hands each message to exactly one consumer, so a uat
     * backend polling the production queue will win some fraction of production's events,
     * fail to match the account against its own database, and discard them. Production
     * sees no error at all — those resources just do not appear until the next reconcile.
     *
     * <p>
     * Logged loudly at startup rather than enforced, because a deliberate second
     * environment with its own queue is a legitimate setup and this class cannot tell the
     * two apart from the URL alone.
     *
     * <p>
     * Deliberately bound to {@link ApplicationReadyEvent} and <strong>not</strong>
     * {@code @PostConstruct}. A {@code @PostConstruct} here forces this bean — and
     * therefore {@code SqsClient} and the whole of {@code AwsConfig} — to initialise before
     * {@code AsyncConfig} has published the {@code TaskDecorator} that {@code AwsConfig}
     * hard-injects. When that injection fails, every bean {@code AwsConfig} defines
     * silently disappears, and startup dies with an unrelated-looking
     * "no qualifying bean of type StsClient" against whichever service happened to be
     * constructed first. Running after the context is ready has none of that ordering
     * pressure and logs exactly the same thing.
     */
    @EventListener(ApplicationReadyEvent.class)
    void warnOnSharedQueueAcrossEnvironments() {
        if (!enabled || queueUrl == null || queueUrl.isBlank()) {
            return;
        }
        boolean nonProd = !"prod".equalsIgnoreCase(activeProfile);
        boolean looksLikeSharedQueue = queueUrl.contains("xamops-inventory-events.fifo");
        if (nonProd && looksLikeSharedQueue) {
            log.warn("⚠️ [InventoryEvent] Profile '{}' has events ENABLED against the shared "
                    + "queue {}. SQS delivers each message once, so this environment will "
                    + "consume and discard events belonging to whichever environment owns that "
                    + "queue. Either disable xamops.inventory.events.enabled here, or deploy a "
                    + "separate inventory engine stack and override the queue URL.",
                    activeProfile, queueUrl);
        } else {
            log.info("[InventoryEvent] Listener active on profile '{}' — queue {}",
                    activeProfile, queueUrl);
        }
    }

    public InventoryEventListener(SqsClient sqsClient,
            CloudAccountRepository cloudAccountRepository,
            InventoryEventIngestService ingestService,
            RedisCacheService redisCache,
            InventoryChangeNotifier notifier,
            SchedulingEnvironmentConfig envConfig,
            ObjectMapper objectMapper,
            SecurityFindingIngestService securityIngest) {
        this.sqsClient = sqsClient;
        this.cloudAccountRepository = cloudAccountRepository;
        this.ingestService = ingestService;
        this.redisCache = redisCache;
        this.notifier = notifier;
        this.envConfig = envConfig;
        this.objectMapper = objectMapper;
        this.securityIngest = securityIngest;
    }

    /**
     * Long-polls for events.
     *
     * <p>
     * 20s wait, batch of 10. Long polling is what keeps SQS request count — and therefore
     * cost — at roughly 130k/month regardless of how many customer accounts feed the
     * queue, comfortably inside the free tier.
     */
    @Scheduled(fixedDelayString = "${xamops.inventory.events.poll-interval-ms:5000}")
    public void pollEvents() {
        if (!enabled || queueUrl == null || queueUrl.isBlank()) {
            return;
        }
        if (!envConfig.isSchedulingEnabled()) {
            return;
        }

        try {
            List<Message> messages = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(20)
                    .build()).messages();

            if (messages.isEmpty()) {
                return;
            }

            TenantContext.setCurrentTenant(envConfig.getCurrentTenantId());
            try {
                processBatch(messages);
            } finally {
                TenantContext.clear();
            }
        } catch (Exception e) {
            // Never rethrow: an exception here would suppress all future executions of
            // this scheduled method, silently killing the event pipeline.
            log.error("[InventoryEvent] Poll cycle failed: {}", e.getMessage());
        }
    }

    private void processBatch(List<Message> messages) {
        // Accumulated per account so one eviction and one push covers the whole batch.
        Map<String, int[]> changesByAccount = new HashMap<>();
        // Keyed by AWS account id but holding the resolved connection: the dashboard push
        // needs cloud_account.id, and handleMessage has already looked it up.
        Map<String, CloudAccount> accountsTouched = new HashMap<>();
        // Security findings accumulated per account as {created, updated, resolved}. Kept
        // separate from changesByAccount because the two drive different follow-up: a finding
        // does not invalidate an inventory projection, and a new resource is not a finding.
        Map<String, int[]> findingsByAccount = new HashMap<>();
        List<DeleteMessageBatchRequestEntry> toDelete = new ArrayList<>();

        for (Message message : messages) {
            try {
                CloudAccount touched = handleMessage(message.body(), changesByAccount, findingsByAccount);
                if (touched != null) {
                    accountsTouched.put(touched.getAwsAccountId(), touched);
                }
                // Deleted on success AND on "recognised but not actionable": a message we
                // cannot act on will never become actionable, and leaving it would have
                // it redelivered until it hit the DLQ.
                toDelete.add(DeleteMessageBatchRequestEntry.builder()
                        .id(message.messageId())
                        .receiptHandle(message.receiptHandle())
                        .build());
            } catch (Exception e) {
                // Left on the queue on purpose. A transient DB failure should be retried
                // by SQS rather than dropped, and repeated failures land in the DLQ where
                // they are visible.
                log.error("[InventoryEvent] Failed to process message {} — leaving for retry: {}",
                        message.messageId(), e.getMessage());
            }
        }

        if (!toDelete.isEmpty()) {
            try {
                sqsClient.deleteMessageBatch(DeleteMessageBatchRequest.builder()
                        .queueUrl(queueUrl)
                        .entries(toDelete)
                        .build());
            } catch (Exception e) {
                // At-least-once delivery means a failed delete causes reprocessing, which
                // is safe: apply() is idempotent by ARN.
                log.warn("[InventoryEvent] Batch delete failed, messages will redeliver: {}",
                        e.getMessage());
            }
        }

        for (Map.Entry<String, CloudAccount> entry : accountsTouched.entrySet()) {
            int[] counts = changesByAccount.getOrDefault(entry.getKey(), new int[] { 0, 0 });
            if (counts[0] > 0 || counts[1] > 0) {
                invalidateProjections(entry.getKey());
                notifyUi(entry.getValue(), counts[0], counts[1]);
            }
            int[] findings = findingsByAccount.get(entry.getKey());
            // Logged when security events arrived even if none became a finding, so the
            // "events in, findings out" ratio is visible in one place.
            if (findings != null && findings.length > 3 && findings[3] > 0
                    && findings[0] == 0 && findings[2] == 0) {
                log.info("[SecFinding] Account {} — {} security event(s) evaluated, no new or "
                        + "resolved findings", entry.getKey(), findings[3]);
            }
            if (findings != null && (findings[0] > 0 || findings[2] > 0)) {
                // Deliberately no cache eviction here. The obvious move would be to evict
                // securityFindings-{account}, but that key is rebuilt by
                // SecurityService.getComprehensiveSecurityFindings, which fans out live
                // CloudTrail, Flow Log, IAM, KMS, RDS and S3 describes across every active
                // region — so evicting on each finding would schedule a multi-region scan per
                // event. The pages read SecurityFindingProjectionService ahead of their caches
                // instead, which needs no invalidation at all.
                log.info("[SecFinding] Account {} — {} new, {} updated, {} resolved finding(s)",
                        entry.getKey(), findings[0], findings[1], findings[2]);
            }
        }
    }

    /**
     * Applies one envelope.
     *
     * @return the connection touched, or null if the message was not actionable
     */
    private CloudAccount handleMessage(String body, Map<String, int[]> changesByAccount,
            Map<String, int[]> findingsByAccount) throws Exception {
        JsonNode root = objectMapper.readTree(body);

        String awsAccountId = root.path("account").asText(null);
        if (awsAccountId == null || awsAccountId.isBlank()) {
            log.warn("[InventoryEvent] Message without an account id, discarding");
            return null;
        }

        Optional<CloudAccount> accountOpt = cloudAccountRepository.findByProviderAccountId(awsAccountId);
        if (accountOpt.isEmpty()) {
            // A customer stack outliving its XamOps account. Discard quietly rather than
            // retrying forever.
            log.warn("[InventoryEvent] Event for unknown AWS account {}, discarding", awsAccountId);
            return null;
        }
        CloudAccount account = accountOpt.get();

        String action = root.path("action").asText(InventoryEventIngestService.ACTION_CHANGED);
        String region = root.path("region").asText(null);
        String source = root.path("source").asText("");
        // Tracked so the product can tell an account whose stack predates the exposure
        // detectors from one where nothing dangerous has happened. Both produce no findings.
        int schemaVersion = root.path("schemaVersion").asInt(0);

        // Security findings, before the ARN check below.
        //
        // Ordering is the whole fix. The xamops-inventory-security rule has forwarded
        // Security Hub, GuardDuty and Config events with their detail attached since the
        // stack shipped, but those events carry no resource ARNs — so they reached the
        // "no ARN, leave it to the reconciler" branch, were logged at debug and deleted
        // from the queue. Every finding AWS produced for every customer was being received
        // and discarded.
        if (SecurityFindingIngestService.isSecuritySource(source)) {
            SecurityFindingIngestService.Result result = securityIngest.ingest(account, root);
            int[] counts = findingsByAccount.computeIfAbsent(awsAccountId, k -> new int[4]);
            // Counted whether or not it produced a finding. Without this, "the security rule
            // fired 16 times and no finding exists" is indistinguishable from "the rule never
            // fired" — both look like silence, and telling them apart meant correlating
            // CloudWatch rule metrics against the database by hand.
            counts[3]++;
            counts[0] += result.created;
            counts[1] += result.updated;
            counts[2] += result.resolved;
            if (!result.changed()) {
                // The common benign reasons are an already-archived or already-resolved
                // finding, and a Config event that is a configuration snapshot rather than a
                // compliance transition. Named at debug so a quiet page can be explained
                // without adding noise to a healthy one.
                log.debug("[SecFinding] {} event ({}) for {} produced no finding — already "
                        + "resolved, suppressed, or not a finding-bearing detail shape",
                        source, root.path("detailType").asText(""), awsAccountId);
            }
            ingestService.markEventReceived(account, schemaVersion);
            return account;
        }

        // XamOps-derived findings run alongside the inventory upsert rather than instead of
        // it: DeleteTrail both removes a resource from the inventory and is a finding, and
        // returning early here would lose the first.
        try {
            SecurityFindingIngestService.Result detected = securityIngest.ingestDetected(account, root);
            if (detected.changed()) {
                // Same width as the security branch above: a detector finding and a
                // Security Hub finding land in the same counter array, and a shorter array
                // here would make the batch summary read past its end.
                int[] counts = findingsByAccount.computeIfAbsent(awsAccountId, k -> new int[4]);
                counts[0] += detected.created;
                counts[1] += detected.updated;
                counts[2] += detected.resolved;
            }
        } catch (Exception e) {
            // Detector failure must not cost us the inventory update, which is the older and
            // more load-bearing half of this listener.
            log.warn("[InventoryEvent] Detector failed for {} on account {}: {}",
                    root.path("eventName").asText(""), awsAccountId, e.getMessage());
        }

        JsonNode arns = root.path("arns");
        if (!arns.isArray() || arns.isEmpty()) {
            // No ARN resolved by the forwarder. Something changed but we cannot say what,
            // so let the next reconcile catch it — the alternative, triggering a full
            // account scan per unattributable event, is how this becomes expensive.
            log.debug("[InventoryEvent] {} event for {} carried no ARN; leaving to reconciler",
                    root.path("eventName").asText(""), awsAccountId);
            ingestService.markEventReceived(account, schemaVersion);
            // Still returned as touched, because a detector above may have produced a finding
            // from this event even though it named no resource the inventory can hold.
            return account;
        }

        int created = 0;
        int deleted = 0;
        for (JsonNode arnNode : arns) {
            String arn = arnNode.asText(null);
            if (arn == null || arn.isBlank()) {
                continue;
            }
            InventoryEventIngestService.Outcome outcome =
                    ingestService.apply(account, arn, action, region);
            if (outcome == InventoryEventIngestService.Outcome.CREATED) {
                created++;
            } else if (outcome == InventoryEventIngestService.Outcome.DELETED) {
                deleted++;
            }
        }

        ingestService.markEventReceived(account, schemaVersion);

        int[] counts = changesByAccount.computeIfAbsent(awsAccountId, k -> new int[2]);
        counts[0] += created;
        counts[1] += deleted;
        return account;
    }

    /**
     * Evicts the page caches whose contents just became wrong.
     *
     * <p>
     * The point of enumerating keys rather than flushing by wildcard is blast radius: a
     * pattern eviction would also drop the Cost Explorer and reservation caches, whose
     * contents did not change and which cost real money to rebuild. Only inventory-derived
     * projections are listed here. Cost, metric and Compute Optimizer caches are
     * deliberately left alone — a resource created seconds ago has no cost or metric data
     * for AWS to give us.
     */
    private void invalidateProjections(String awsAccountId) {
        List<String> keys = List.of(
                "cloudlistResources-" + awsAccountId,
                "groupedCloudlistResources-" + awsAccountId,
                "regionStatus-" + awsAccountId,
                "vpcQuotaAlerts-" + awsAccountId,
                "networkQuotaAlerts-" + awsAccountId,
                "taggingCompliance-" + awsAccountId);
        for (String key : keys) {
            try {
                redisCache.evict(key);
            } catch (Exception e) {
                log.debug("[InventoryEvent] Could not evict {}: {}", key, e.getMessage());
            }
        }
        log.debug("[InventoryEvent] Invalidated {} inventory projections for {}",
                keys.size(), awsAccountId);
    }

    private void notifyUi(CloudAccount account, int created, int deleted) {
        // Delegated so the cloud list topic and the dashboard widget topic are always
        // published together. See InventoryChangeNotifier.
        notifier.inventoryChanged(account, created, deleted, -1, "event");
        log.info("[InventoryEvent] Account {} changed via events — {} added, {} removed",
                account.getAwsAccountId(), created, deleted);
    }
}
