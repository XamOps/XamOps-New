package com.xammer.cloud.repository;

import com.xammer.cloud.domain.WorkloadVerticalAction;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Durable auto-apply / manual-apply action rows.
 *
 * <p>Every lookup is scoped by {@code clusterConfigId}, which is
 * {@code kubernetes_cluster_config.id} — a surrogate key, never the ambiguous
 * {@code cloud_account_id}. Callers resolve the cluster config from an account the caller is
 * entitled to before coming here, so a query cannot reach another tenant's row by construction.
 *
 * <p>The activity feed's page ({@link #findActivityPage}) is scoped by {@code clientId}
 * <em>and</em> {@code clusterConfigId}, and its filters are the column predicates below, composed by
 * the caller. So are the per-day summary's two reads ({@link #findFirstAppliedAt} and
 * {@link #findSummaryRows}).
 */
@Repository
public interface WorkloadVerticalActionRepository extends JpaRepository<WorkloadVerticalAction, Long>,
        JpaSpecificationExecutor<WorkloadVerticalAction> {

    /** The newest action for one workload — what endpoint 1 reports as {@code lastAction}. */
    Optional<WorkloadVerticalAction>
    findTopByClusterConfigIdAndNamespaceAndWorkloadKindAndWorkloadNameOrderByAppliedAtDesc(
            Long clusterConfigId, String namespace, String workloadKind, String workloadName);

    /**
     * Every action for one cluster, newest first, for the read endpoint's single pass over a
     * cluster's workloads: the caller keeps the first row it sees per workload key.
     */
    List<WorkloadVerticalAction> findByClusterConfigIdOrderByAppliedAtDesc(Long clusterConfigId);

    // ---------------------------------------------------------------- endpoint 1's lastAction

    /**
     * One workload's newest qualifying row: when, whether a dry run, and the outcome, without the
     * JSON bodies.
     */
    record LastActionRow(String namespace, String workloadKind, String workloadName, Instant appliedAt,
                         Boolean dryRun, String outcome) {
    }

    /**
     * The newest row per workload ({@code namespace/kind/name}) of one cluster that is not a dry run,
     * whose outcome is none of {@code notOutcomes} and whose kind is not {@code notKind}, newest
     * first. The rows left out are left out here, in the query, so a caller reads one short row per
     * workload rather than the cluster's whole history. Two rows of one workload at the same instant
     * both come back, the higher id first; a caller keeps the first. A row without
     * {@code applied_at}, which no writer produces, is never the newest.
     *
     * @param notOutcomes the outcomes that never qualify; at least one
     * @param notKind     the {@code workload_kind} that never qualifies
     */
    default List<LastActionRow> findLastActions(Long clusterConfigId, Collection<String> notOutcomes,
                                                String notKind) {
        if (clusterConfigId == null || notOutcomes == null || notOutcomes.isEmpty() || notKind == null) {
            throw new IllegalArgumentException("lastAction is read per cluster config, with the rows it skips");
        }
        return findLastActionTuples(clusterConfigId, notOutcomes, notKind).stream()
                .map(tuple -> new LastActionRow((String) tuple[0], (String) tuple[1], (String) tuple[2],
                        (Instant) tuple[3], (Boolean) tuple[4], (String) tuple[5]))
                .toList();
    }

    /** The query behind {@link #findLastActions}; call that instead. */
    @Query("select a.namespace, a.workloadKind, a.workloadName, a.appliedAt, a.dryRun, a.outcome"
            + " from WorkloadVerticalAction a"
            + " where a.clusterConfigId = :clusterConfigId"
            + " and a.dryRun = false and a.outcome not in :outcomes and a.workloadKind <> :hpaKind"
            + " and (a.namespace, a.workloadKind, a.workloadName, a.appliedAt) in ("
            + "   select b.namespace, b.workloadKind, b.workloadName, max(b.appliedAt)"
            + "   from WorkloadVerticalAction b"
            + "   where b.clusterConfigId = :clusterConfigId"
            + "   and b.dryRun = false and b.outcome not in :outcomes and b.workloadKind <> :hpaKind"
            + "   group by b.namespace, b.workloadKind, b.workloadName)"
            + " order by a.appliedAt desc, a.id desc")
    List<Object[]> findLastActionTuples(@Param("clusterConfigId") Long clusterConfigId,
                                        @Param("outcomes") Collection<String> outcomes,
                                        @Param("hpaKind") String hpaKind);

    /** The feed's order: newest first, the id breaking a tie, so a keyset page is total. */
    Sort ACTIVITY_ORDER = Sort.by(Sort.Order.desc("appliedAt"), Sort.Order.desc("id"));

    /**
     * Where the previous page ended: its last row's instant and id. The next page starts strictly
     * after it in {@link #ACTIVITY_ORDER}, so a row inserted meanwhile is never repeated or skipped.
     */
    record ActivityCursor(Instant appliedAt, Long id) {

        public ActivityCursor {
            if (appliedAt == null || id == null) {
                throw new IllegalArgumentException("a cursor needs the row's applied_at and id");
            }
        }
    }

    /**
     * One page of one cluster's activity, newest first, for one tenant.
     *
     * @param filter the caller's filters, composed from the predicates below; null for none
     * @param after  the previous page's last row, or null for the first page
     * @param limit  at most this many rows; a caller asks for one more than it shows to learn
     *               whether an older page exists
     */
    default List<WorkloadVerticalAction> findActivityPage(Long clientId, Long clusterConfigId,
                                                          Specification<WorkloadVerticalAction> filter,
                                                          ActivityCursor after, int limit) {
        requireTenant(clientId, clusterConfigId);
        requirePositive(limit);
        Specification<WorkloadVerticalAction> query = tenant(clientId, clusterConfigId);
        if (filter != null) {
            query = query.and(filter);
        }
        if (after != null) {
            query = query.and(olderThan(after));
        }
        return findBy(query, rows -> rows.sortBy(ACTIVITY_ORDER).limit(limit).all());
    }

    // ---------------------------------------------------------------- the per-day summary's reads

    /** Oldest first, the id breaking a tie: the order {@link #findFirstAppliedAt} reads. */
    Sort OLDEST_FIRST = Sort.by(Sort.Order.asc("appliedAt"), Sort.Order.asc("id"));

    /**
     * When the oldest of the tenant's rows on this cluster that match {@code filter} was applied, or
     * empty when there is none. Reads one row.
     *
     * @param filter composed from the predicates below; null for every row
     */
    default Optional<Instant> findFirstAppliedAt(Long clientId, Long clusterConfigId,
                                                 Specification<WorkloadVerticalAction> filter) {
        requireTenant(clientId, clusterConfigId);
        Specification<WorkloadVerticalAction> query = tenant(clientId, clusterConfigId);
        if (filter != null) {
            query = query.and(filter);
        }
        Optional<WorkloadVerticalAction> oldest = findBy(query, rows -> rows.sortBy(OLDEST_FIRST).first());
        return oldest.map(WorkloadVerticalAction::getAppliedAt);
    }

    /**
     * How many leading characters of {@code after_json} {@link #findSummaryRows} reads: more than
     * the longest prefix a row's result is told by, so the summary never loads a JSON body.
     */
    int SUMMARY_AFTER_JSON_PREFIX = 64;

    /**
     * One row as the per-day summary counts it: what the result mapping reads, without the JSON
     * bodies.
     *
     * @param afterJsonStart the first {@link #SUMMARY_AFTER_JSON_PREFIX} characters of
     *                       {@code after_json}, or null when the row has none
     */
    record SummaryRow(Long id, Instant appliedAt, String workloadKind, String outcome, Boolean dryRun,
                      String afterJsonStart) {
    }

    /**
     * The tenant's rows of one cluster applied within {@code [from, to]}, newest first, as
     * {@link SummaryRow}s, at most {@code limit} of them: the summary's bounded scan.
     */
    default List<SummaryRow> findSummaryRows(Long clientId, Long clusterConfigId, Instant from, Instant to,
                                             int limit) {
        requireTenant(clientId, clusterConfigId);
        requirePositive(limit);
        if (from == null || to == null) {
            throw new IllegalArgumentException("the summary scan needs both ends of its window");
        }
        return findSummaryTuples(clientId, clusterConfigId, from, to, Limit.of(limit)).stream()
                .map(tuple -> new SummaryRow((Long) tuple[0], (Instant) tuple[1], (String) tuple[2],
                        (String) tuple[3], (Boolean) tuple[4], (String) tuple[5]))
                .toList();
    }

    /** The query behind {@link #findSummaryRows}; call that instead. */
    @Query("select a.id, a.appliedAt, a.workloadKind, a.outcome, a.dryRun,"
            + " substring(a.afterJson, 1, " + SUMMARY_AFTER_JSON_PREFIX + ")"
            + " from WorkloadVerticalAction a"
            + " where a.clientId = :clientId and a.clusterConfigId = :clusterConfigId"
            + " and a.appliedAt >= :from and a.appliedAt <= :to"
            + " order by a.appliedAt desc, a.id desc")
    List<Object[]> findSummaryTuples(@Param("clientId") Long clientId,
                                     @Param("clusterConfigId") Long clusterConfigId,
                                     @Param("from") Instant from, @Param("to") Instant to, Limit limit);

    private static void requireTenant(Long clientId, Long clusterConfigId) {
        if (clientId == null || clusterConfigId == null) {
            throw new IllegalArgumentException("the activity feed is scoped by client and cluster config");
        }
    }

    private static void requirePositive(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, got " + limit);
        }
    }

    // ---------------------------------------------------------------- the feed's column predicates

    /**
     * The tenant's rows of one registered cluster. A row with no {@code applied_at} cannot be placed
     * on the timeline, and no writer produces one, so it is left out rather than sorted arbitrarily.
     */
    static Specification<WorkloadVerticalAction> tenant(Long clientId, Long clusterConfigId) {
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("clientId"), clientId),
                cb.equal(root.get("clusterConfigId"), clusterConfigId),
                cb.isNotNull(root.get("appliedAt")));
    }

    /** Strictly after {@code cursor} in {@link #ACTIVITY_ORDER}. */
    static Specification<WorkloadVerticalAction> olderThan(ActivityCursor cursor) {
        return (root, query, cb) -> cb.or(
                cb.lessThan(root.<Instant>get("appliedAt"), cursor.appliedAt()),
                cb.and(cb.equal(root.get("appliedAt"), cursor.appliedAt()),
                        cb.lessThan(root.<Long>get("id"), cursor.id())));
    }

    /** {@code applied_at} within {@code [from, to]}; a null bound is open. */
    static Specification<WorkloadVerticalAction> appliedBetween(Instant from, Instant to) {
        return (root, query, cb) -> {
            if (from != null && to != null) {
                return cb.between(root.<Instant>get("appliedAt"), from, to);
            }
            if (from != null) {
                return cb.greaterThanOrEqualTo(root.<Instant>get("appliedAt"), from);
            }
            if (to != null) {
                return cb.lessThanOrEqualTo(root.<Instant>get("appliedAt"), to);
            }
            return cb.conjunction();
        };
    }

    static Specification<WorkloadVerticalAction> kindIs(String kind) {
        return (root, query, cb) -> cb.equal(root.get("workloadKind"), kind);
    }

    static Specification<WorkloadVerticalAction> kindIsNot(String kind) {
        return (root, query, cb) -> cb.notEqual(root.get("workloadKind"), kind);
    }

    static Specification<WorkloadVerticalAction> outcomeIn(Collection<String> outcomes) {
        return (root, query, cb) -> root.get("outcome").in(outcomes);
    }

    /** {@code dry_run} is the given value; the column is NOT NULL. */
    static Specification<WorkloadVerticalAction> dryRun(boolean dryRun) {
        return (root, query, cb) -> cb.equal(root.get("dryRun"), dryRun);
    }

    /** {@code after_json} begins with any of {@code prefixes}, matched literally. */
    static Specification<WorkloadVerticalAction> afterJsonStartsWithAny(Collection<String> prefixes) {
        return (root, query, cb) -> cb.and(cb.isNotNull(root.get("afterJson")),
                cb.or(prefixes.stream()
                        .map(prefix -> cb.like(root.<String>get("afterJson"), literal(prefix) + "%", '\\'))
                        .toArray(jakarta.persistence.criteria.Predicate[]::new)));
    }

    /** {@code after_json} is null or begins with none of {@code prefixes}. */
    static Specification<WorkloadVerticalAction> afterJsonStartsWithNone(Collection<String> prefixes) {
        return (root, query, cb) -> cb.or(cb.isNull(root.get("afterJson")),
                cb.not(cb.or(prefixes.stream()
                        .map(prefix -> cb.like(root.<String>get("afterJson"), literal(prefix) + "%", '\\'))
                        .toArray(jakarta.persistence.criteria.Predicate[]::new))));
    }

    /** {@code namespace} is exactly {@code namespace}. */
    static Specification<WorkloadVerticalAction> namespaceIs(String namespace) {
        return (root, query, cb) -> cb.equal(root.get("namespace"), namespace);
    }

    /** {@code workload_name} is exactly {@code name}: one workload's or HPA's own rows. */
    static Specification<WorkloadVerticalAction> nameIs(String name) {
        return (root, query, cb) -> cb.equal(root.get("workloadName"), name);
    }

    /** {@code workload_name} contains {@code text}, case-insensitively and literally. */
    static Specification<WorkloadVerticalAction> nameContains(String text) {
        String pattern = "%" + literal(text.toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.<String>get("workloadName")), pattern, '\\');
    }

    /** {@code text} as a LIKE pattern that matches only itself: the wildcards and the escape escaped. */
    static String literal(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
