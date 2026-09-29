package com.xammer.cloud.service;

import com.xammer.cloud.domain.AwsCloudResource;
import com.xammer.cloud.repository.AwsCloudResourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * One resource, one key — whichever of its several names the caller arrived holding.
 *
 * <h2>The problem</h2>
 * Two pages in XamOps talk about the same AWS resource and call it different things.
 * The Cloud List reads the inventory, where a resource is its short id:
 * {@code i-089ccf1b5a839880a}, {@code my-bucket}. The Cost page's per-resource view reads
 * the Cost and Usage Report, where a resource is whatever AWS chose to put in
 * {@code line_item_resource_id} — the same short id for EC2, EBS and S3, but a full ARN
 * for RDS, Lambda, DynamoDB, OpenSearch and the load balancers. The report carries no
 * column saying which form a given row used.
 *
 * <p>Labels are stored against a resource id, so without reconciliation the two pages
 * write to different rows for the same resource: a label put on an RDS instance from the
 * cost view lands under {@code arn:aws:rds:…:db:prod} and is invisible to the Cloud List,
 * which looks under {@code prod}. Both pages would appear to work, and would quietly
 * disagree — the worst of the available failures, because nothing surfaces it.
 *
 * <h2>Why the inventory is the arbiter</h2>
 * {@code aws_cloud_resource} is the only table holding a resource's ARN and its short id
 * on one row, so it is the only thing that can map between them. It also holds
 * {@code displayType} ("EC2 Instance", "RDS Instance"), which is the type vocabulary every
 * other consumer filters on — the CUR's {@code product_product_name} ("Amazon Elastic
 * Compute Cloud - Compute") is a billing label, not a type, and storing it would leave the
 * "view by label" page and the per-label cost fallback reading two vocabularies at once.
 *
 * <p>Resolution is therefore also a normalisation: the canonical form is always what the
 * inventory says, and a caller's own guess at the type or region is only used for resources
 * the inventory has never seen.
 *
 * <h2>What it does not do</h2>
 * Nothing is invented. An id the inventory cannot match comes back absent rather than
 * guessed at, and the caller keeps what it had. Deriving the short id by taking the tail of
 * the ARN is tempting and wrong: it happens to be right for EC2 and S3 and is wrong for
 * anything whose inventory id is not its ARN's last segment, which would put the mismatch
 * back while looking like it had been fixed.
 */
@Service
public class ResourceIdResolver {

    private static final Logger logger = LoggerFactory.getLogger(ResourceIdResolver.class);

    /**
     * Postgres accepts far more, but a single {@code IN} list this long is already past the
     * point where the planner stops using the index. Chunking keeps each statement sane
     * regardless of how many resources a caller paged in.
     */
    private static final int CHUNK = 500;

    private final AwsCloudResourceRepository resourceRepo;

    public ResourceIdResolver(AwsCloudResourceRepository resourceRepo) {
        this.resourceRepo = resourceRepo;
    }

    /**
     * A resource's identity as the inventory records it.
     *
     * @param resourceId   the short form — the key everything else in XamOps stores
     * @param resourceType the UI-facing type ("EC2 Instance"), matching CloudListService
     * @param resourceName the resource's name, or null where AWS reports none
     * @param region       the region, or null for global resources
     */
    public record CanonicalResource(String resourceId, String resourceType,
                                    String resourceName, String region) {
    }

    /**
     * Maps each given id to the resource it identifies, for those the inventory knows.
     *
     * <p>Keys of the returned map are the ids as they were passed in — including ones that
     * were already canonical, which map to themselves. Ids with no inventory match are
     * absent; callers treat that as "use what I have" rather than as an error, because an
     * account can legitimately be billed for resources Resource Explorer does not index.
     *
     * @param cloudAccountId the internal {@code cloud_account} row id, not the AWS account id
     */
    public Map<String, CanonicalResource> resolve(Long cloudAccountId, Collection<String> rawIds) {
        if (cloudAccountId == null || rawIds == null || rawIds.isEmpty()) return Map.of();

        // Deduplicated because a caller's list may repeat an id, and blanks stripped because
        // resource-less charges (tax, support) carry an empty id and must never be resolved
        // to anything — every one of them would collide on the same key.
        List<String> lookup = new ArrayList<>(new LinkedHashSet<>(rawIds).stream()
                .filter(id -> id != null && !id.isBlank())
                .toList());
        if (lookup.isEmpty()) return Map.of();

        List<AwsCloudResource> rows = new ArrayList<>();
        for (int from = 0; from < lookup.size(); from += CHUNK) {
            rows.addAll(resourceRepo.findByCloudAccountIdAndAnyIdentifier(
                    cloudAccountId, lookup.subList(from, Math.min(from + CHUNK, lookup.size()))));
        }
        if (rows.isEmpty()) return Map.of();

        // Indexed under both identifiers, so a caller holding either lands on the same row.
        // ACTIVE wins a collision: an ARN can be held by both a live row and the tombstone of
        // an earlier connection, and the live one is the resource the reader is looking at.
        Map<String, AwsCloudResource> byIdentifier = new HashMap<>();
        for (AwsCloudResource row : rows) {
            index(byIdentifier, row.getArn(), row);
            index(byIdentifier, row.getResourceId(), row);
        }

        Map<String, CanonicalResource> resolved = new HashMap<>();
        for (String raw : lookup) {
            AwsCloudResource row = byIdentifier.get(raw);
            if (row != null) {
                resolved.put(raw, new CanonicalResource(
                        row.getResourceId(), row.getDisplayType(), row.getName(), row.getRegion()));
            }
        }

        if (resolved.size() < lookup.size()) {
            logger.debug("[ResourceIdResolver] {} of {} ids resolved for cloudAccount {}",
                    resolved.size(), lookup.size(), cloudAccountId);
        }
        return resolved;
    }

    private static void index(Map<String, AwsCloudResource> into, String key, AwsCloudResource row) {
        if (key == null || key.isBlank()) return;
        AwsCloudResource existing = into.get(key);
        if (existing == null || (!AwsCloudResource.STATUS_ACTIVE.equals(existing.getStatus())
                && AwsCloudResource.STATUS_ACTIVE.equals(row.getStatus()))) {
            into.put(key, row);
        }
    }
}
