package com.xammer.cloud.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.LabelAuditChange;
import com.xammer.cloud.domain.LabelAuditEvent;
import com.xammer.cloud.domain.ResourceLabel;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.LabelAuditChangeRepository;
import com.xammer.cloud.repository.LabelAuditEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The trail of label changes: who changed which labels and shares, on what, when, from which
 * screen, and what they were before.
 *
 * <h2>Shape</h2>
 * One {@link LabelAuditEvent} per user action and one {@link LabelAuditChange} per target it
 * changed, so a label applied to forty connected resources is one entry in the account's history
 * and still appears in the history of each of the forty. Every change keeps the target's full set
 * of labels and shares on both sides — "prod 40%, uat 40%" to "prod 60%, uat 40%" — so an entry
 * can be read on its own, without replaying the ones before it.
 *
 * <h2>Transactions</h2>
 * {@link #record} joins the caller's transaction: a change and its entry commit or roll back
 * together, because a change that could land without its entry leaves the trail incomplete in
 * exactly the case it exists for. {@link #recordRejected} runs in its own, because the change it
 * describes has already rolled back.
 *
 * <p>Append-only: nothing here updates or deletes an entry. Reads are scoped to the caller's
 * client, and to an account the caller has already been checked against.
 */
@Service
public class LabelAuditService {

    private static final Logger logger = LoggerFactory.getLogger(LabelAuditService.class);

    public static final String RESOURCE = "RESOURCE";
    public static final String SERVICE = "SERVICE";
    public static final String LABEL = "LABEL";

    private static final String OK = "OK";
    private static final String REJECTED = "REJECTED";

    /** Change rows stored per event. Far above any hand-made change; a label on more is summarised. */
    static final int MAX_CHANGES_PER_EVENT = 500;
    /** Targets kept for a refused attempt, which is debugging information, not a record of state. */
    private static final int MAX_REJECTED_TARGETS = 50;
    /** Changes returned per event in a history read; the count covers all of them. */
    private static final int MAX_CHANGES_SHOWN = 50;
    private static final int MAX_PAGE = 100;

    /** Where a change came from, as the caller tags it. Anything else is stored as unknown. */
    private static final Set<String> SOURCES = Set.of(
            "COST_RESOURCE_PANEL", "COST_SERVICE_ROW", "CASCADE", "CLOUD_LIST", "LABELS_PAGE");

    private static final ObjectMapper JSON = new ObjectMapper();

    public enum Action {
        LABEL_CREATED,
        LABEL_UPDATED,
        /** The label and every assignment of it; the changes list what it was on. */
        LABEL_DELETED,
        /** The full set of labels and shares on one resource or service, saved at once. */
        LABELS_SET,
        /** One label added, at the whole cost, to one or many resources. */
        LABEL_ASSIGNED,
        LABEL_REMOVED
    }

    /** A label as it stood on a resource or service. A null percent means the whole cost. */
    public record ShareSnapshot(Long labelId, String name, String color, BigDecimal percent) {
        public static ShareSnapshot of(ResourceLabel label, BigDecimal percent) {
            return new ShareSnapshot(label.getId(), label.getName(), label.getColor(), percent);
        }
    }

    /** A label's own fields, for its create, edit and delete entries. */
    public record LabelSnapshot(Long id, String name, String color, String description) {
        public static LabelSnapshot of(ResourceLabel label) {
            return new LabelSnapshot(label.getId(), label.getName(), label.getColor(), label.getDescription());
        }
    }

    /**
     * One target an event changed, with its state either side; null means there was none.
     *
     * @param cloudAccountId the account the target lives in; null for a label
     */
    public record Change(Long cloudAccountId, String targetType, String targetId, String targetName,
                         Object before, Object after) {
    }

    /** What a refused write was trying to do, as the caller described it. */
    public record Attempt(Action action, String accountRef, String source, Long labelId, String targetType,
                          List<Target> targets, Object attempted) {
    }

    public record Target(String id, String name) {
    }

    // ── Views ────────────────────────────────────────────────────────────────────

    public record HistoryPage(List<EventView> events, boolean hasMore) {
    }

    /**
     * @param at          ISO-8601 instant, with its zone, so the browser can show it in local time
     * @param changeCount the changes visible from this account, of which {@code changes} lists the first
     */
    public record EventView(Long id, String at, String actor, String action, String outcome, String source,
                            Long labelId, String labelName, String labelColor, String detail,
                            int changeCount, List<ChangeView> changes) {
    }

    /** {@code before} and {@code after} are the stored JSON, passed through as text. */
    public record ChangeView(String targetType, String targetId, String targetName, String before, String after) {
    }

    private final LabelAuditEventRepository events;
    private final LabelAuditChangeRepository changes;
    private final CloudAccountRepository accounts;
    private final ResourceIdResolver idResolver;

    public LabelAuditService(LabelAuditEventRepository events,
                             LabelAuditChangeRepository changes,
                             CloudAccountRepository accounts,
                             ResourceIdResolver idResolver) {
        this.events = events;
        this.changes = changes;
        this.accounts = accounts;
        this.idResolver = idResolver;
    }

    /** The caller's source tag if it is one we know, else null. Metadata, never a reason to refuse a write. */
    public static String source(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase(Locale.ROOT);
        return SOURCES.contains(s) ? s : null;
    }

    // ── Writing ──────────────────────────────────────────────────────────────────

    /**
     * Records a change that has just been made, in the caller's transaction. Does nothing when
     * {@code changed} is empty: a save that changed nothing is not an event.
     *
     * @param account    the account changed, or null for a client-level change to a label itself
     * @param accountRef the account id as the caller sent it
     * @param label      the label the action was about, when it was about one
     */
    @Transactional
    public void record(Long clientId, String actor, String source, Action action, CloudAccount account,
                       String accountRef, ResourceLabel label, List<Change> changed) {
        if (changed == null || changed.isEmpty()) return;

        LabelAuditEvent e = event(clientId, actor, source, action, OK);
        if (account != null) {
            e.setCloudAccountId(account.getId());
            e.setAccountRef(truncate(accountRef, 64));
        }
        if (label != null) {
            e.setLabelId(label.getId());
            e.setLabelName(truncate(label.getName(), 100));
            e.setLabelColor(truncate(label.getColor(), 7));
        }
        e.setChangeCount(changed.size());
        if (changed.size() > MAX_CHANGES_PER_EVENT) {
            e.setDetail("Listed " + MAX_CHANGES_PER_EVENT + " of " + changed.size() + " changes.");
        }
        LabelAuditEvent saved = events.save(e);

        List<LabelAuditChange> rows = new ArrayList<>();
        for (Change c : changed.subList(0, Math.min(changed.size(), MAX_CHANGES_PER_EVENT))) {
            rows.add(row(saved.getId(), c, c.before(), c.after()));
        }
        changes.saveAll(rows);
        logger.info("[LabelAudit] client={} actor={} {} account={} label={} changes={}", clientId, actor,
                action, account == null ? "-" : account.getId(), label == null ? "-" : label.getId(), changed.size());
    }

    /**
     * Records a write the server refused, in its own transaction because the write rolled back.
     *
     * <p>The caller should not let a failure here replace the original error: wrap the call.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRejected(Long clientId, String actor, Attempt attempt, String reason) {
        LabelAuditEvent e = event(clientId, actor, attempt.source(), attempt.action(), REJECTED);
        CloudAccount account = attempt.accountRef() == null ? null : findAccount(attempt.accountRef());
        if (attempt.accountRef() != null) {
            e.setAccountRef(truncate(attempt.accountRef(), 64));
            e.setCloudAccountId(account == null ? null : account.getId());
        }
        e.setLabelId(attempt.labelId());
        e.setDetail(truncate(reason, 1000));
        List<Target> targets = attempt.targets() == null ? List.of() : attempt.targets();
        e.setChangeCount(targets.size());
        LabelAuditEvent saved = events.save(e);

        boolean single = targets.size() == 1;
        Long accountId = LABEL.equals(attempt.targetType()) || account == null ? null : account.getId();
        List<LabelAuditChange> rows = new ArrayList<>();
        for (Target t : targets.subList(0, Math.min(targets.size(), MAX_REJECTED_TARGETS))) {
            Change c = new Change(accountId, attempt.targetType(), t.id(), t.name(), null,
                    single ? attempt.attempted() : null);
            rows.add(row(saved.getId(), c, null, c.after()));
        }
        changes.saveAll(rows);
        logger.info("[LabelAudit] client={} actor={} {} rejected: {}", clientId, actor, attempt.action(), reason);
    }

    private static LabelAuditEvent event(Long clientId, String actor, String source, Action action, String outcome) {
        LabelAuditEvent e = new LabelAuditEvent();
        e.setClientId(clientId);
        e.setActor(truncate(actor, 100));
        e.setAction(action.name());
        e.setOutcome(outcome);
        e.setSource(source(source));
        e.setCreatedAt(Instant.now());
        return e;
    }

    private static LabelAuditChange row(Long eventId, Change c, Object before, Object after) {
        LabelAuditChange r = new LabelAuditChange();
        r.setEventId(eventId);
        r.setCloudAccountId(c.cloudAccountId());
        r.setTargetType(c.targetType());
        r.setTargetId(truncate(c.targetId(), 512));
        r.setTargetName(truncate(c.targetName(), 255));
        r.setBeforeJson(json(before));
        r.setAfterJson(json(after));
        return r;
    }

    /** Same lookup order as the label service: provider id, then AWS account id or GCP project. */
    private CloudAccount findAccount(String ref) {
        return accounts.findByProviderAccountId(ref)
                .orElseGet(() -> accounts.findByAwsAccountIdOrGcpProjectId(ref, ref).stream().findFirst().orElse(null));
    }

    // ── Reading ──────────────────────────────────────────────────────────────────

    /** Everything that changed labels on one account, plus the client's label edits, newest first. */
    @Transactional(readOnly = true)
    public HistoryPage accountHistory(Long clientId, CloudAccount account, Long beforeId, int size) {
        int n = pageSize(size);
        List<LabelAuditEvent> found = events.findForAccount(clientId, account.getId(), cursor(beforeId),
                PageRequest.of(0, n + 1));
        boolean more = found.size() > n;
        List<LabelAuditEvent> shown = more ? found.subList(0, n) : found;
        // A label's own row has no account; every other row is only shown in its own account.
        return new HistoryPage(views(shown,
                c -> c.getCloudAccountId() == null || account.getId().equals(c.getCloudAccountId())), more);
    }

    /**
     * Everything that changed one resource or one service, newest first.
     *
     * @param targetId for a resource, whichever id form the caller holds; matched against every
     *                 form it may be stored under
     */
    @Transactional(readOnly = true)
    public HistoryPage targetHistory(Long clientId, CloudAccount account, String targetType, String targetId,
                                     Long beforeId, int size) {
        String type = targetType == null ? "" : targetType.trim().toUpperCase(Locale.ROOT);
        if (!RESOURCE.equals(type) && !SERVICE.equals(type)) {
            throw new IllegalArgumentException("targetType must be RESOURCE or SERVICE.");
        }
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("targetId is required with targetType.");
        }
        Set<String> ids = new LinkedHashSet<>();
        ids.add(targetId.trim());
        if (RESOURCE.equals(type)) {
            ResourceIdResolver.CanonicalResource known =
                    idResolver.resolve(account.getId(), List.of(targetId.trim())).get(targetId.trim());
            if (known != null) ids.add(known.resourceId());
        }

        int n = pageSize(size);
        List<Long> found = changes.findEventIdsForTarget(clientId, account.getId(), type, ids, cursor(beforeId),
                PageRequest.of(0, n + 1));
        boolean more = found.size() > n;
        List<Long> shownIds = more ? found.subList(0, n) : found;
        if (shownIds.isEmpty()) return new HistoryPage(List.of(), false);
        List<LabelAuditEvent> shown = events.findForClient(clientId, shownIds);
        return new HistoryPage(views(shown, c -> account.getId().equals(c.getCloudAccountId())
                && type.equals(c.getTargetType()) && ids.contains(c.getTargetId())), more);
    }

    private List<EventView> views(List<LabelAuditEvent> shown, Predicate<LabelAuditChange> visible) {
        if (shown.isEmpty()) return List.of();
        Map<Long, List<LabelAuditChange>> byEvent = new LinkedHashMap<>();
        for (LabelAuditChange c : changes.findByEventIdInOrderByIdAsc(shown.stream().map(LabelAuditEvent::getId).toList())) {
            if (visible.test(c)) byEvent.computeIfAbsent(c.getEventId(), k -> new ArrayList<>()).add(c);
        }
        List<EventView> out = new ArrayList<>(shown.size());
        for (LabelAuditEvent e : shown) {
            List<LabelAuditChange> rows = byEvent.getOrDefault(e.getId(), List.of());
            List<ChangeView> listed = rows.stream().limit(MAX_CHANGES_SHOWN)
                    .map(c -> new ChangeView(c.getTargetType(), c.getTargetId(), c.getTargetName(),
                            c.getBeforeJson(), c.getAfterJson()))
                    .toList();
            out.add(new EventView(e.getId(), e.getCreatedAt() == null ? null : e.getCreatedAt().toString(),
                    e.getActor(), e.getAction(), e.getOutcome(), e.getSource(), e.getLabelId(), e.getLabelName(),
                    e.getLabelColor(), e.getDetail(), rows.size(), listed));
        }
        return out;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static long cursor(Long beforeId) {
        return beforeId == null ? Long.MAX_VALUE : beforeId;
    }

    private static int pageSize(int size) {
        return Math.max(1, Math.min(MAX_PAGE, size));
    }

    private static String json(Object value) {
        if (value == null) return null;
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // Snapshots are plain records and lists, so this does not happen; if it ever does, the
            // entry keeps its who, when and what, and loses only this side's detail.
            logger.warn("[LabelAudit] Could not serialise a snapshot: {}", e.getMessage());
            return null;
        }
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
