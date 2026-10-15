package com.xammer.cloud.service;

import com.xammer.cloud.domain.AwsCloudResource;
import com.xammer.cloud.repository.AwsCloudResourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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
 *
 * <h2>The join invariant {@link #curIdentifiers} holds</h2>
 * An identifier may be used to look costs up in the CUR only if it is a <b>single claimant
 * across the whole account, counting rows of every status</b>. An ARN satisfies that by
 * construction — {@code uq_aws_cloud_resource_cloudacct_arn} is a unique index on
 * {@code (cloud_account_id, arn)}. A short {@code resource_id} satisfies it only when proven,
 * because no unique index covers that column and AWS lets two resources of different services
 * carry the same name. Unproven short ids are dropped silently rather than defended afterwards.
 *
 * <p>Status is irrelevant to that uniqueness test: the CUR has no concept of a deleted resource,
 * so a tombstone's short id collides exactly as hard as a live one's. <b>Answerability</b> is a
 * separate question and is judged on {@code ACTIVE} claimants alone — a sole-claimant tombstone
 * still supplies its identifiers, because pricing a resource deleted mid-month is one of the
 * reasons the per-resource cost views exist.
 *
 * <p>One carve-out, added after the invariant was measured on account {@code 605134457560}: an
 * <b>AWS-generated</b> id is usable even with several claimants, because the inventory indexes one
 * machine under several service ARNs. {@code i-00e153b89ae72b8c3} is claimed by its EC2 instance
 * ARN, its SSM managed-instance ARN and an STS assumed-role ARN — three strings, one physical
 * instance. Counting distinct ARNs therefore asks whether the strings differ, not whether they
 * denote different resources: on that account 183 short ids had two or more live claimants and
 * <b>all 183 resolved to a single ARN tail</b>, so not one was a real collision, while the drop
 * took 73 of the 80 active EC2 instance ids with it. {@link #isAwsGeneratedId} is where the
 * distinction lives, and says why it is a property of the id rather than of the ARN.
 *
 * <p>{@link #index} is deliberately <em>not</em> reused for any of this. Its ACTIVE-wins
 * tie-break is right for a live row and the tombstone of the same resource, and wrong for a
 * collision between two different resources, where it would pick one at random and then emit
 * that resource's unique ARN with full confidence.
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

    /** The id space AWS mints and never reissues — see {@link #isAwsGeneratedId}. */
    private static final Pattern AWS_GENERATED_ID = Pattern.compile("^[a-z]{1,12}-[0-9a-f]{8,}$");

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
     * @param arn          the full ARN from the inventory row, or null when the row has none
     */
    public record CanonicalResource(String resourceId, String resourceType,
                                    String resourceName, String region, String arn) {
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
        List<String> lookup = clean(rawIds);
        if (lookup.isEmpty()) return Map.of();

        List<AwsCloudResource> rows = rowsFor(cloudAccountId, lookup);
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
                        row.getResourceId(), row.getDisplayType(), row.getName(), row.getRegion(),
                        row.getArn()));
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

    // ── The CUR join ────────────────────────────────────────────────────────

    /**
     * Deduplicated, non-blank, in first-seen order.
     *
     * <p>Blanks are stripped because resource-less charges (tax, support) carry an empty id and
     * must never resolve to anything — every one of them would collide on the same key.
     */
    private static List<String> clean(Collection<String> rawIds) {
        return new ArrayList<>(new LinkedHashSet<>(rawIds).stream()
                .filter(id -> id != null && !id.isBlank())
                .toList());
    }

    /**
     * Every inventory row claiming any of these identifiers, read in chunks.
     *
     * <p>Extracted so {@link #resolve} and {@link #curIdentifiers} read the inventory identically
     * and the {@code CHUNK} decision lives in one place.
     *
     * @param cloudAccountId the internal {@code cloud_account} row id, not the AWS account id
     * @param lookup         cleaned identifiers; may be matched against either {@code arn} or
     *                       {@code resource_id}, since the repository query tries both
     */
    private List<AwsCloudResource> rowsFor(Long cloudAccountId, Collection<String> lookup) {
        List<String> ids = lookup instanceof List<String> list ? list : new ArrayList<>(lookup);
        List<AwsCloudResource> rows = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += CHUNK) {
            rows.addAll(resourceRepo.findByCloudAccountIdAndAnyIdentifier(
                    cloudAccountId, ids.subList(from, Math.min(from + CHUNK, ids.size()))));
        }
        return rows;
    }

    /**
     * Which {@code line_item_resource_id} values could name each of these resources, and which
     * node ids could name them in the Cloud Map graph.
     *
     * <p>Serves both directions of the connectivity join, which is why it is one method: a CUR id
     * in gives the forms that could name a graph node, and a graph node id in gives the forms that
     * could appear in {@code line_item_resource_id}. They are the same pair, and the repository
     * query already matches {@code arn IN :ids OR resourceId IN :ids}, so an id arriving in either
     * vocabulary finds the same row. {@link #resolve} cannot serve either direction:
     * {@link CanonicalResource} carries the short id and not the ARN, and returning that single
     * field as "the id the graph uses" is wrong for every type whose describe-path node id is the
     * full ARN — a load balancer is named {@code my-lb} there while its {@code resource_id} is the
     * ARN tail {@code 50dc6c495c0c9188}.
     *
     * <p>Two reads, in this order, and the second is what makes the invariant provable rather than
     * assumed:
     * <ol>
     *   <li>over the requested ids, which finds the row each one names;</li>
     *   <li>over the non-blank short ids those rows yielded, which finds <em>every</em> resource
     *       claiming each of them. A read keyed on the requested ids cannot see a second resource
     *       that merely shares a short id, so without this pass the collision is invisible and the
     *       join prices an arbitrary resource with full confidence. ARNs skip it — the unique index
     *       is the proof.</li>
     * </ol>
     *
     * <p>Candidates are matched by equality and nothing else. An id with no inventory row is its
     * own only candidate, which is correct for every service whose CUR id is its bare id (EC2, EBS,
     * NAT, S3) and finds nothing for the rest — a blank, which is the right answer when the
     * inventory cannot vouch for the mapping. A type whose graph id is neither identifier form
     * stays blank too; there is no tail matching and no type-aware rescue anywhere in here, because
     * a tail is a name and names are unique only per service per region.
     *
     * @param cloudAccountId the internal {@code cloud_account} row id, not the AWS account id
     * @param inventoryIds   the ids to resolve — CUR identifiers, graph node ids, or a mixture
     * @return one entry per cleaned requested id, never null
     */
    public CurIdentifiers curIdentifiers(Long cloudAccountId, Collection<String> inventoryIds) {
        if (cloudAccountId == null || inventoryIds == null || inventoryIds.isEmpty()) {
            return new CurIdentifiers(Map.of(), Map.of(), Map.of(), Set.of());
        }
        List<String> requested = clean(inventoryIds);
        if (requested.isEmpty()) {
            return new CurIdentifiers(Map.of(), Map.of(), Map.of(), Set.of());
        }

        List<AwsCloudResource> rows = rowsFor(cloudAccountId, requested);

        // Pass 2. Only the short ids, and only the ones nobody asked about: pass 1 is already
        // complete for a requested id, since the query matched that string against both columns.
        Set<String> shortIds = new LinkedHashSet<>();
        for (AwsCloudResource row : rows) {
            String shortId = row.getResourceId();
            if (shortId != null && !shortId.isBlank() && !requested.contains(shortId)) {
                shortIds.add(shortId);
            }
        }
        List<AwsCloudResource> secondPass = shortIds.isEmpty()
                ? List.of()
                : rowsFor(cloudAccountId, new ArrayList<>(shortIds));

        // Keyed by the row's own primary key so a row returned by both passes is counted once.
        // Counting it twice would not change the claim-key sets below, but it would make the
        // tie-break arbitrary between two copies of one row.
        Map<Long, AwsCloudResource> union = new LinkedHashMap<>();
        for (AwsCloudResource row : rows) union.putIfAbsent(keyOf(row), row);
        for (AwsCloudResource row : secondPass) union.putIfAbsent(keyOf(row), row);

        Map<String, List<AwsCloudResource>> claimants = new HashMap<>();
        for (AwsCloudResource row : union.values()) {
            claim(claimants, row.getArn(), row);
            claim(claimants, row.getResourceId(), row);
        }

        Map<String, List<String>> pricing = new LinkedHashMap<>();
        Map<String, List<String>> nodeCandidates = new LinkedHashMap<>();
        Map<String, CanonicalResource> resolved = new LinkedHashMap<>();
        Set<String> ambiguous = new LinkedHashSet<>();
        String firstCollision = null;

        for (String id : requested) {
            List<AwsCloudResource> claiming = claimants.getOrDefault(id, List.of());
            List<AwsCloudResource> live = claiming.stream()
                    .filter(row -> AwsCloudResource.STATUS_ACTIVE.equals(row.getStatus()))
                    .toList();

            if (!namesOneResource(id, live)) {
                // Two live resources answer to this name — a name, necessarily, because several
                // claimants on an id AWS generated are facets of one machine and collapse inside
                // namesOneResource. There is no non-arbitrary way to say which of two resources
                // the caller meant, and resolving it to one of them would emit that row's
                // unique ARN — a confident figure that may be another team's database. The id is
                // still returned, marked, and still carries itself as a locate candidate: it is in
                // the graph, so reporting it as absent from the topology would be a false
                // statement with no retry path.
                pricing.put(id, List.of());
                nodeCandidates.put(id, List.of(id));
                ambiguous.add(id);
                if (firstCollision == null) firstCollision = id;
                continue;
            }

            AwsCloudResource row = pick(live.isEmpty() ? claiming : live);
            if (row == null) {
                // No inventory row: the id is its own only candidate, and it is usable by
                // construction because nothing else claims it.
                pricing.put(id, List.of(id));
                nodeCandidates.put(id, List.of(id));
                continue;
            }

            // The locate list is NOT filtered by the invariant, deliberately. State "not in the
            // account topology" is decided by no candidate matching a graph node, so dropping a
            // colliding short id here would produce that state for a resource that is in the
            // graph — and it would buy nothing, because two resources sharing a resource_id
            // produce two graph nodes with the same id, which the client's index merges.
            List<String> locate = dedupe(List.of(
                    nullToEmpty(row.getResourceId()), nullToEmpty(row.getArn())));
            nodeCandidates.put(id, locate.isEmpty() ? List.of(id) : locate);

            List<String> usable = locate.stream()
                    .filter(candidate -> namesOneResource(
                            candidate, claimants.getOrDefault(candidate, List.of())))
                    .toList();
            pricing.put(id, usable);
            resolved.put(id, new CanonicalResource(
                    row.getResourceId(), row.getDisplayType(), row.getName(), row.getRegion(),
                    row.getArn()));
            if (usable.isEmpty()) {
                ambiguous.add(id);
                if (firstCollision == null) firstCollision = id;
            } else if (usable.size() < locate.size() && firstCollision == null) {
                firstCollision = locate.stream().filter(c -> !usable.contains(c)).findFirst().orElse(null);
            }
        }

        if (!ambiguous.isEmpty() || firstCollision != null) {
            // The colliding identifier only, never the whole list: a request can carry a hundred
            // ids and the one that collided is the only one worth reading about.
            logger.debug("[ResourceIdResolver] {} of {} ids resolved for cloudAccount {},"
                            + " {} unusable, first collision on '{}'",
                    resolved.size(), requested.size(), cloudAccountId, ambiguous.size(),
                    firstCollision);
        }
        return new CurIdentifiers(pricing, nodeCandidates, resolved, ambiguous);
    }

    /**
     * Which row this is, for deduplicating the union of the two inventory reads.
     *
     * <p>The primary key when there is one. A row with no id has not been persisted, which in
     * practice means a hand-built test fixture, and object identity is the right fallback for
     * exactly that case: the two reads return the same instances, so {@code putIfAbsent} still
     * collapses a row both passes found, while two distinct fixtures stay two rows. It is
     * deliberately not the ARN — {@link #distinctClaims} reuses this value as the claim key for an
     * ARN-less row, and keying on a blank ARN there would collapse every such row into one
     * resource and hide a collision.
     *
     * @param row an inventory row from either pass
     * @return a key unique to that row within one call
     */
    private static Long keyOf(AwsCloudResource row) {
        return row.getId() != null ? row.getId() : (long) System.identityHashCode(row);
    }

    private static void claim(Map<String, List<AwsCloudResource>> into, String key,
                              AwsCloudResource row) {
        if (key == null || key.isBlank()) return;
        into.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
    }

    /**
     * How many distinct resources these rows are.
     *
     * <p>Counted by ARN, which is what makes "two rows" and "two resources" different things: a
     * live row and the tombstone of an earlier connection of the same resource share an ARN and are
     * one resource, so they do not make an identifier ambiguous. A row with no ARN — which the
     * unique index should make impossible — counts as its own resource rather than collapsing every
     * such row into one.
     */
    private static long distinctClaims(List<AwsCloudResource> rows) {
        return rows.stream()
                .map(row -> row.getArn() == null || row.getArn().isBlank()
                        ? "#" + keyOf(row)
                        : row.getArn())
                .distinct()
                .count();
    }

    /**
     * Is this identifier safe to ask the CUR about — does it name at most one resource?
     *
     * <p>One distinct claim is one resource, by {@link #distinctClaims}. Several claims are one
     * resource too when the identifier is AWS-generated, because the inventory indexes one machine
     * under several service ARNs and AWS does not hand the same generated id to two resources. On
     * account {@code 605134457560} every one of the 183 multi-claimant short ids was that shape of
     * duplicate — all 183 resolved to a single ARN tail — and refusing them blanked 73 of the 80
     * active EC2 instance ids, the most cost-relevant type on the page.
     *
     * <p>A customer-chosen name still needs its single claim. Two unrelated resources genuinely can
     * both be called {@code prod}, and nothing in the name says otherwise.
     *
     * @param id    the identifier about to enter, or be refused, the CUR {@code IN} predicate
     * @param rows  every inventory row claiming it, of every status — status is irrelevant here
     */
    private static boolean namesOneResource(String id, List<AwsCloudResource> rows) {
        return distinctClaims(rows) <= 1 || isAwsGeneratedId(id);
    }

    /**
     * Did AWS mint this id, or did a customer choose it?
     *
     * <p>A short lowercase prefix, a hyphen, then eight or more hex digits: {@code i-}, {@code vol-},
     * {@code snap-}, {@code eni-}, {@code sg-}, {@code subnet-}, {@code vpc-}, {@code vpce-},
     * {@code nat-}, {@code igw-}, {@code rtb-}, {@code acl-}, {@code ami-}, {@code eipalloc-},
     * {@code fsvol-}, {@code lt-}, {@code dopt-} and their siblings. AWS owns that id space, so
     * several claimants on one of these are facets of one resource rather than a collision.
     *
     * <p>Deliberately the generic shape and not an allow-list of those prefixes. The property being
     * tested is "AWS minted it", which the shape expresses directly, whereas an allow-list has to be
     * remembered every time AWS adds a service — and the cost of forgetting is silent: a whole
     * resource type reverts to "cost is not attributable" with no failing test anywhere, which is
     * precisely the bug this carve-out exists to undo. The residual hole is a customer who names two
     * resources something like {@code prod-deadbeef}, lowercase and hex after the hyphen; that is
     * narrow, and it is the direction an allow-list would be wrong in far more often.
     *
     * <p>Comparing ARN <em>tails</em> instead was rejected. It repairs the same 183 ids, and it also
     * accepts {@code arn:aws:rds:…:db:prod} against {@code arn:aws:eks:…:cluster/prod}, which share
     * the tail {@code prod} and are two different resources — the exact confusion the invariant
     * exists to prevent. ARN structure cannot separate the two situations; only the id can.
     *
     * @param id a non-blank identifier — a short resource id, or an ARN, which never matches
     */
    private static boolean isAwsGeneratedId(String id) {
        return AWS_GENERATED_ID.matcher(id).matches();
    }

    /**
     * The row whose identifiers are used, or null when there are none.
     *
     * <p>Greatest {@code last_seen_at} wins. For same-ARN duplicates that is the live row's own
     * most recent sighting; for two tombstones it is a bounded tie-break — both resources are gone
     * either way, and the more recently seen one is the one whose CUR rows fall in the month being
     * asked about.
     */
    private static AwsCloudResource pick(List<AwsCloudResource> rows) {
        return rows.stream()
                .max(Comparator.comparing(row -> row.getLastSeenAt() == null
                        ? Instant.EPOCH
                        : row.getLastSeenAt()))
                .orElse(null);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Non-blank, order-preserving, deduplicated. */
    private static List<String> dedupe(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values.stream()
                .filter(value -> !value.isBlank())
                .toList()));
    }

    /**
     * What the CUR may be asked about each requested id, and what the graph may be searched for.
     *
     * <p>Two lists per id because they answer two different questions. The pricing list is filtered
     * by the join invariant and can be empty; the locate list is not, and never is.
     *
     * @param byRequestedId                 pricing candidates per requested id, {@code resource_id}
     *                                      before {@code arn}, each one proven to name a single
     *                                      resource across every status — one claimant, or several
     *                                      claimants on an id AWS generated, which are facets of
     *                                      one machine. Empty for an id with nothing safe left to
     *                                      ask about.
     * @param nodeIdCandidatesByRequestedId the identifiers a graph node could be named with, in the
     *                                      order a client should try them — {@code resource_id}
     *                                      first, because that is what the inventory-plane graph
     *                                      path emits and that is the preferred path. Never empty:
     *                                      an id with no row, or one with two live claimants,
     *                                      carries itself.
     * @param resolved                      the inventory row's descriptive fields per requested id,
     *                                      for display only. Absent for an unresolved id and for a
     *                                      name two live resources answer to, where naming one of
     *                                      the two would be the same arbitrary pick the pricing
     *                                      list refuses to make. Present for a multi-facet
     *                                      AWS-generated id, described by its most recently seen
     *                                      facet — the facets are one resource, so the type shown
     *                                      is whichever service last saw the machine.
     * @param ambiguous                     requested ids left with no usable pricing candidate.
     *                                      Membership means exactly "nothing left to ask about" —
     *                                      an id that lost one candidate to a collision and kept
     *                                      another is priced normally and is not here.
     */
    public record CurIdentifiers(Map<String, List<String>> byRequestedId,
                                 Map<String, List<String>> nodeIdCandidatesByRequestedId,
                                 Map<String, CanonicalResource> resolved,
                                 Set<String> ambiguous) {

        /** Every identifier the CUR query should ask about, deduplicated across requested ids. */
        public Set<String> allCandidates() {
            Set<String> all = new LinkedHashSet<>();
            byRequestedId.values().forEach(all::addAll);
            return all;
        }

        /**
         * Candidate to the requested ids holding it, for attribution.
         *
         * <p>A list and not a single id because this is not injective, and the design does not
         * claim it is: two requested ids resolving to the same inventory row are aliases — a caller
         * asking by {@code resource_id} and by {@code arn} in one request — and both must show the
         * figure. The invariant is what keeps two <em>different</em> resources out of one entry; a
         * last-write-wins map would silently blank one of two aliases.
         */
        public Map<String, List<String>> owners() {
            Map<String, List<String>> owners = new LinkedHashMap<>();
            byRequestedId.forEach((requestedId, candidates) -> candidates.forEach(candidate ->
                    owners.computeIfAbsent(candidate, k -> new ArrayList<>()).add(requestedId)));
            return owners;
        }
    }
}
