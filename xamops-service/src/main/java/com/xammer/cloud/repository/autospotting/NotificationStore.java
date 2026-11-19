package com.xammer.cloud.repository.autospotting;

import com.xammer.cloud.domain.autospotting.AutoSpottingNotification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The JDBC statements behind the AutoSpotting notification outbox (design-ux-ops.md section 7.1). JdbcTemplate
 * only, like {@link SpotGroupLifecycleRepository}: the table has no entity.
 *
 * <p>{@link #insert} runs {@link Propagation#MANDATORY}: a notice is written in the transaction of the state
 * change that causes it, so a rolled-back transition leaves no row and a crash can't lose one. The insert is
 * {@code ON CONFLICT (dedupe_key) DO NOTHING}, so a retried transition can't duplicate a notice.
 * {@link #claimNextDue} locks one unsent row with {@code FOR UPDATE SKIP LOCKED}, so two notifier instances
 * never send the same row.
 */
@Repository
public class NotificationStore {

    /** A row to insert. {@code payloadJson} must already be redacted. */
    public record NewNotification(String eventType, String audience, boolean mandatory, long clientId,
                                  long cloudAccountId, UUID groupId, UUID operationId, String dedupeKey,
                                  String payloadJson) {
    }

    /** What a state change needs to know about the operation behind it. */
    public record OperationFacts(String kind, String errorCode, UUID planId) {
    }

    /** Who confirmed a plan and the reason they gave. */
    public record PlanConfirmation(String confirmedBy, String reason) {
    }

    private static final String COLUMNS = "id, event_type, audience, mandatory, client_id, cloud_account_id,"
            + " group_id, operation_id, dedupe_key, payload::text AS payload, created_at, email_sent_at,"
            + " attempts, last_error, read_by::text AS read_by";

    private static final RowMapper<AutoSpottingNotification> MAPPER = (rs, i) -> new AutoSpottingNotification(
            rs.getLong("id"), rs.getString("event_type"), rs.getString("audience"), rs.getBoolean("mandatory"),
            rs.getLong("client_id"), rs.getLong("cloud_account_id"), rs.getObject("group_id", UUID.class),
            rs.getObject("operation_id", UUID.class), rs.getString("dedupe_key"), rs.getString("payload"),
            instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("email_sent_at")),
            rs.getInt("attempts"), rs.getString("last_error"), rs.getString("read_by"));

    private final JdbcTemplate jdbc;

    public NotificationStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    // ── write side ───────────────────────────────────────────────────────────

    /** Insert one notice; false when its dedupe key already exists (nothing is written then). */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean insert(NewNotification n) {
        int rows = jdbc.update("INSERT INTO autospotting_notification (event_type, audience, mandatory, client_id,"
                        + " cloud_account_id, group_id, operation_id, dedupe_key, payload)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)"
                        + " ON CONFLICT (dedupe_key) DO NOTHING",
                n.eventType(), n.audience(), n.mandatory(), n.clientId(), n.cloudAccountId(), n.groupId(),
                n.operationId(), n.dedupeKey(), n.payloadJson());
        return rows == 1;
    }

    /** The group's newest transition id (the transition the caller just inserted, under the group lease). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long latestTransitionId(UUID groupId) {
        return jdbc.queryForObject("SELECT max(id) FROM spot_group_transition WHERE group_id = ?", Long.class,
                groupId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<OperationFacts> operationFacts(UUID operationId) {
        List<OperationFacts> rows = jdbc.query("SELECT kind, error_code, plan_id FROM spot_operation WHERE id = ?",
                (rs, i) -> new OperationFacts(rs.getString("kind"), rs.getString("error_code"),
                        rs.getObject("plan_id", UUID.class)), operationId);
        return rows.stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PlanConfirmation> planConfirmation(UUID planId) {
        List<PlanConfirmation> rows = jdbc.query("SELECT confirmed_by, confirm_reason FROM spot_plan WHERE id = ?",
                (rs, i) -> new PlanConfirmation(rs.getString("confirmed_by"), rs.getString("confirm_reason")),
                planId);
        return rows.stream().findFirst();
    }

    /** The account's display name, or empty when the account row is gone or the table is absent. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<String> accountName(long cloudAccountId) {
        if (!tableExists("cloud_account")) {
            return Optional.empty();
        }
        List<String> rows = jdbc.queryForList("SELECT account_name FROM cloud_account WHERE id = ?", String.class,
                cloudAccountId);
        return rows.stream().filter(s -> s != null && !s.isBlank()).findFirst();
    }

    // ── delivery side ────────────────────────────────────────────────────────

    /** Lock and return the oldest due, unsent customer row, skipping rows another notifier holds. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<AutoSpottingNotification> claimNextDue(int maxAttempts) {
        List<AutoSpottingNotification> rows = jdbc.query("SELECT " + COLUMNS + " FROM autospotting_notification"
                + " WHERE email_sent_at IS NULL AND audience = 'CUSTOMER' AND attempts < ?"
                + " AND (next_attempt_at IS NULL OR next_attempt_at <= now())"
                + " ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED", MAPPER, maxAttempts);
        return rows.stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markSent(long id, String note) {
        jdbc.update("UPDATE autospotting_notification SET email_sent_at = now(), attempts = attempts + 1,"
                + " last_error = ?, next_attempt_at = NULL WHERE id = ? AND email_sent_at IS NULL", note, id);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markFailed(long id, String error, Instant nextAttemptAt) {
        jdbc.update("UPDATE autospotting_notification SET attempts = attempts + 1, last_error = ?,"
                        + " next_attempt_at = ? WHERE id = ? AND email_sent_at IS NULL", error,
                nextAttemptAt == null ? null : Timestamp.from(nextAttemptAt), id);
    }

    // ── in-app read side ─────────────────────────────────────────────────────

    /** A client's notices, newest first. {@code onlyUnreadFor} (a user id) keeps the ones that user hasn't read. */
    @Transactional(readOnly = true)
    public List<AutoSpottingNotification> listForClient(long clientId, Long onlyUnreadFor, int limit) {
        String unread = onlyUnreadFor == null ? "" : " AND NOT read_by @> to_jsonb(?::bigint)";
        String sql = "SELECT " + COLUMNS + " FROM autospotting_notification WHERE client_id = ?"
                + " AND audience = 'CUSTOMER'" + unread + " ORDER BY id DESC LIMIT ?";
        return onlyUnreadFor == null
                ? jdbc.query(sql, MAPPER, clientId, limit)
                : jdbc.query(sql, MAPPER, clientId, onlyUnreadFor, limit);
    }

    /** Mark a notice read by a user; false when it isn't this client's or the user already read it. */
    @Transactional
    public boolean markRead(long id, long clientId, long userId) {
        return jdbc.update("UPDATE autospotting_notification SET read_by = read_by || to_jsonb(?::bigint)"
                + " WHERE id = ? AND client_id = ? AND audience = 'CUSTOMER'"
                + " AND NOT read_by @> to_jsonb(?::bigint)", userId, id, clientId, userId) == 1;
    }

    // ── sweeps that turn a standing condition or a ledger fact into a notice ──

    /**
     * Insert a DEGRADED_LONG notice for each group that has been DEGRADED for at least {@code minutes}. The
     * dedupe key carries the transition that started the episode, so one episode is told once and a later
     * episode is told again.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int insertDegradedLong(int minutes) {
        return jdbc.update("INSERT INTO autospotting_notification (event_type, audience, mandatory, client_id,"
                + " cloud_account_id, group_id, dedupe_key, payload)"
                + " SELECT 'DEGRADED_LONG', 'CUSTOMER', true, g.client_id, g.cloud_account_id, g.id,"
                + "        'degraded:' || g.id || ':' || t.id,"
                + "        jsonb_build_object('groupKey', g.group_key, 'provider', g.provider,"
                + "                           'minutes', ?::int, 'since', t.effective_at)"
                + " FROM spot_group g"
                + " JOIN LATERAL (SELECT id, to_state, effective_at FROM spot_group_transition"
                + "               WHERE group_id = g.id ORDER BY id DESC LIMIT 1) t ON true"
                + " WHERE g.state = 'DEGRADED' AND t.to_state = 'DEGRADED'"
                + "   AND t.effective_at <= now() - (?::int * interval '1 minute')"
                + " ON CONFLICT (dedupe_key) DO NOTHING", minutes, minutes);
    }

    /** STATEMENT_READY for statements closed since {@code since}; dedupe is per client, period and account. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int insertStatementReady(Instant since) {
        if (!tableExists("savings_statement")) {
            return 0;
        }
        return jdbc.update("INSERT INTO autospotting_notification (event_type, audience, mandatory, client_id,"
                + " cloud_account_id, dedupe_key, payload)"
                + " SELECT 'STATEMENT_READY', 'CUSTOMER', false, s.client_id, s.cloud_account_id,"
                + "        'stmt:' || s.client_id || ':' || to_char(s.statement_month, 'YYYY-MM') || ':'"
                + "            || s.cloud_account_id,"
                + "        jsonb_build_object('provider', s.provider,"
                + "                           'period', to_char(s.statement_month, 'YYYY-MM'),"
                + "                           'currency', s.currency,"
                + "                           'totalBillable', s.total_billable::text)"
                + " FROM savings_statement s"
                + " WHERE s.status IN ('CLOSED', 'INVOICED') AND s.closed_at >= ?"
                + " ON CONFLICT (dedupe_key) DO NOTHING", Timestamp.from(since));
    }

    /** DISPUTE_UPDATED for disputes raised or resolved since {@code since}; dedupe is per dispute and status. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int insertDisputeUpdates(Instant since) {
        if (!tableExists("savings_dispute") || !tableExists("savings_statement")) {
            return 0;
        }
        return jdbc.update("INSERT INTO autospotting_notification (event_type, audience, mandatory, client_id,"
                + " cloud_account_id, dedupe_key, payload)"
                + " SELECT 'DISPUTE_UPDATED', 'CUSTOMER', false, s.client_id, s.cloud_account_id,"
                + "        'dispute:' || d.id || ':' || d.status,"
                + "        jsonb_build_object('period', to_char(s.statement_month, 'YYYY-MM'),"
                + "                           'lineNo', d.line_no, 'status', d.status)"
                + " FROM savings_dispute d JOIN savings_statement s ON s.id = d.statement_id"
                + " WHERE COALESCE(d.resolved_at, d.raised_at) >= ?"
                + " ON CONFLICT (dedupe_key) DO NOTHING", Timestamp.from(since));
    }

    /** True when {@code table} exists (the ledger tables are absent in the empty-schema harness). */
    private boolean tableExists(String table) {
        Boolean exists = jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, table);
        return Boolean.TRUE.equals(exists);
    }
}
