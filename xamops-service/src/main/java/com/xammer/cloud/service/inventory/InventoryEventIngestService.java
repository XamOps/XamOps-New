package com.xammer.cloud.service.inventory;

import com.xammer.cloud.domain.AwsCloudResource;
import com.xammer.cloud.domain.AwsInventorySyncState;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.AwsCloudResourceRepository;
import com.xammer.cloud.repository.AwsInventorySyncStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Applies one resource change event to the inventory plane.
 *
 * <p>
 * This is the difference between "inventory refreshes in a minute" and "inventory
 * refreshes in seconds". The important property is that it patches a <em>single row</em>:
 * the previous design could only express a change by re-running an account-wide scan, so
 * one new instance cost ~1000 API calls. Here it costs one upsert.
 *
 * <p>
 * Three deliberate constraints:
 *
 * <ul>
 * <li><b>Events never sweep.</b> A DELETED event tombstones exactly the ARN it names.
 * Only the reconciler, which has seen the whole account, is allowed to decide something
 * is absent. An event-driven sweep would let one malformed message empty a customer's
 * inventory.</li>
 * <li><b>Events never advance the sync generation.</b> If they did, the next reconcile
 * would treat every resource the events did not happen to mention as stale and tombstone
 * it. Rows written here inherit the current generation so the reconciler still owns
 * reconciliation.</li>
 * <li><b>A CREATED event enriches, it does not complete.</b> The event tells us an ARN
 * exists; region and type come from the ARN itself, and everything else fills in on the
 * next reconcile. Attempting a describe per event would reintroduce exactly the API load
 * this design removes.</li>
 * </ul>
 */
@Service
public class InventoryEventIngestService {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventIngestService.class);

    public static final String ACTION_CREATED = "CREATED";
    public static final String ACTION_DELETED = "DELETED";
    public static final String ACTION_CHANGED = "CHANGED";

    private final AwsCloudResourceRepository resourceRepository;
    private final AwsInventorySyncStateRepository syncStateRepository;

    public InventoryEventIngestService(AwsCloudResourceRepository resourceRepository,
            AwsInventorySyncStateRepository syncStateRepository) {
        this.resourceRepository = resourceRepository;
        this.syncStateRepository = syncStateRepository;
    }

    /** What the event did, so the caller can decide whether to notify the UI. */
    public enum Outcome {
        /** A resource appeared that we had not seen. This is the one users notice. */
        CREATED,
        /** An existing resource was tombstoned. */
        DELETED,
        /** An existing row was touched but nothing material changed. */
        UPDATED,
        /** Recognised but not actionable — unknown ARN on a delete, unparseable ARN. */
        IGNORED
    }

    /**
     * Applies one event.
     *
     * @param account the resolved tenant account (never null)
     * @param arn     the affected resource ARN
     * @param action  CREATED, DELETED or CHANGED
     * @param region  region from the event envelope, used when the ARN omits it
     */
    @Transactional
    public Outcome apply(CloudAccount account, String arn, String action, String region) {
        if (arn == null || arn.isBlank() || !arn.startsWith("arn:")) {
            return Outcome.IGNORED;
        }

        Optional<AwsCloudResource> existingOpt =
                resourceRepository.findByCloudAccountIdAndArn(account.getId(), arn);
        Instant now = Instant.now();

        if (ACTION_DELETED.equals(action)) {
            if (existingOpt.isEmpty()) {
                // Never seen it, so nothing to tombstone. Common and harmless: a
                // resource created and destroyed between two reconciles.
                return Outcome.IGNORED;
            }
            AwsCloudResource row = existingOpt.get();
            if (AwsCloudResource.STATUS_DELETED.equals(row.getStatus())) {
                return Outcome.UPDATED;
            }
            row.setStatus(AwsCloudResource.STATUS_DELETED);
            row.setDeletedAt(now);
            row.setLastSeenAt(now);
            row.setSource(AwsCloudResource.SOURCE_EVENT);
            resourceRepository.save(row);
            log.info("[InventoryEvent] Tombstoned {} in account {}", arn, account.getAwsAccountId());
            return Outcome.DELETED;
        }

        // CREATED or CHANGED — upsert.
        if (existingOpt.isPresent()) {
            AwsCloudResource row = existingOpt.get();
            boolean wasDeleted = AwsCloudResource.STATUS_DELETED.equals(row.getStatus());
            row.setStatus(AwsCloudResource.STATUS_ACTIVE);
            row.setDeletedAt(null);
            row.setLastSeenAt(now);
            row.setSource(AwsCloudResource.SOURCE_EVENT);
            resourceRepository.save(row);
            if (wasDeleted) {
                // An ARN can genuinely be reused, and a resurrected row should read as
                // new to the user rather than silently reappearing mid-list.
                log.info("[InventoryEvent] Resurrected {} in account {}", arn, account.getAwsAccountId());
                return Outcome.CREATED;
            }
            return Outcome.UPDATED;
        }

        AwsCloudResource row = buildFromArn(account, arn, region, now);
        if (row == null) {
            return Outcome.IGNORED;
        }
        resourceRepository.save(row);
        log.info("[InventoryEvent] Inserted {} ({}) in account {} — visible immediately",
                arn, row.getDisplayType(), account.getAwsAccountId());
        return Outcome.CREATED;
    }

    /**
     * Builds a row from the ARN alone.
     *
     * <p>
     * An ARN is structured enough to yield service, region, type and id, which is all the
     * cloud list needs to display the resource. Tags, state and type-specific details
     * arrive on the next reconcile. Showing the resource immediately with partial
     * attributes is the right trade: the user's question is "is it there yet", and making
     * them wait for a complete record answers a question they did not ask.
     */
    private AwsCloudResource buildFromArn(CloudAccount account, String arn, String eventRegion, Instant now) {
        // arn:partition:service:region:account:resource...
        String[] parts = arn.split(":", 6);
        if (parts.length < 6) {
            log.debug("[InventoryEvent] Unparseable ARN, skipping: {}", arn);
            return null;
        }
        String service = parts[2];
        String arnRegion = parts[3];
        String resourcePart = parts[5];

        if (service.isBlank()) {
            return null;
        }

        String nativeType = nativeTypeFrom(service, resourcePart);
        if (AwsResourceTypeMapper.isSuppressed(nativeType)) {
            return null;
        }

        // Global services (s3, iam, route53, cloudfront) leave the ARN region empty; the
        // event envelope still has one, so prefer the ARN and fall back to the event.
        String region = !arnRegion.isBlank() ? arnRegion : eventRegion;

        AwsCloudResource row = new AwsCloudResource();
        row.setCloudAccountId(account.getId());
        row.setAwsAccountId(account.getAwsAccountId());
        row.setArn(arn);
        row.setResourceId(AwsResourceTypeMapper.resourceIdFromArn(arn));
        row.setName(row.getResourceId());
        row.setService(service);
        row.setNativeType(nativeType);
        row.setDisplayType(AwsResourceTypeMapper.displayTypeFor(nativeType));
        row.setRegion(region);
        row.setTags("{}");
        row.setDetails("{}");
        // Same reasoning as the reconciler: without a state the UI renders "Unknown". A
        // type with a fixed state can be filled in from the ARN alone; one with real state
        // is left null on purpose and resolved by the next reconcile, because inventing
        // "running" for an instance we have not looked at would be a lie the user acts on.
        row.setState(AwsResourceTypeMapper.staticStateFor(nativeType));
        row.setStatus(AwsCloudResource.STATUS_ACTIVE);
        row.setFirstSeenAt(now);
        row.setLastSeenAt(now);
        row.setLaunchTime(now);
        row.setSource(AwsCloudResource.SOURCE_EVENT);
        // Inherit the reconciler's current generation. Using a higher value would make
        // the next sweep tombstone everything the events did not mention.
        row.setSyncGeneration(currentGeneration(account));
        return row;
    }

    /**
     * Derives Resource Explorer's {@code service:type} token from an ARN's resource part.
     *
     * <p>
     * Both {@code instance/i-abc} and {@code db:mydb} encode the type before the
     * separator; an S3 ARN has neither and is just the bucket name. Producing the same
     * token shape the reconciler uses is what lets an event-inserted row and a
     * reconciler-inserted row group together in the UI instead of appearing as two types.
     */
    private String nativeTypeFrom(String service, String resourcePart) {
        if (resourcePart == null || resourcePart.isBlank()) {
            return service;
        }
        int slash = resourcePart.indexOf('/');
        int colon = resourcePart.indexOf(':');
        int cut = -1;
        if (slash >= 0 && colon >= 0) {
            cut = Math.min(slash, colon);
        } else if (slash >= 0) {
            cut = slash;
        } else if (colon >= 0) {
            cut = colon;
        }
        if (cut > 0) {
            return service + ":" + resourcePart.substring(0, cut);
        }
        // No separator: S3-style, where the resource part is the name itself.
        return "s3".equals(service) ? "s3:bucket" : service;
    }

    private long currentGeneration(CloudAccount account) {
        return syncStateRepository.findById(account.getId())
                .map(s -> s.getSyncGeneration() == null ? 0L : s.getSyncGeneration())
                .orElse(0L);
    }

    /** Records that events are flowing, so /status can show event liveness. */
    @Transactional
    public void markEventReceived(CloudAccount account) {
        AwsInventorySyncState state = syncStateRepository.findById(account.getId())
                .orElseGet(() -> {
                    AwsInventorySyncState s = new AwsInventorySyncState();
                    s.setCloudAccountId(account.getId());
                    s.setAwsAccountId(account.getAwsAccountId());
                    return s;
                });
        Instant now = Instant.now();
        state.setLastEventAt(now);
        state.setUpdatedAt(now);
        syncStateRepository.save(state);
    }
}
