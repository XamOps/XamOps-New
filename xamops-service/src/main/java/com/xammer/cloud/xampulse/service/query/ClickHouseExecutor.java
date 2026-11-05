package com.xammer.cloud.xampulse.service.query;

import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The only way to run a read against ClickHouse. It accepts {@link ScopedSql} only and refuses anything that does
 * not carry the tenant predicate, so a query that forgot tenant scoping fails closed instead of reading across tenants.
 *
 * <p>Defence in depth: the reader's row policy also filters on the per-statement setting {@code SQL_xp_tenant}
 * (appended here as a trusted number from the tenant list, never from user text).
 */
@Service("xpClickHouseExecutor")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class ClickHouseExecutor {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseExecutor.class);
    private static final Pattern SET_STATEMENT = Pattern.compile("(?i)(^|[\\s;])SET\\s");
    private static final Pattern SETTINGS_CLAUSE = Pattern.compile("(?i)\\bSETTINGS\\b");

    private final ClickHouseGateway gateway;
    private final XamPulseProperties props;
    private final Map<Long, Semaphore> perTenant = new ConcurrentHashMap<>();

    public ClickHouseExecutor(ClickHouseGateway gateway, XamPulseProperties props) {
        this.gateway = gateway;
        this.props = props;
    }

    public List<Map<String, Object>> query(ScopedSql q) {
        validate(q);
        long tenant = q.tenantIds().get(0);
        Semaphore sem = perTenant.computeIfAbsent(tenant,
                t -> new Semaphore(Math.max(1, props.getLimits().getConcurrentQueriesPerTenant())));
        boolean acquired;
        try {
            acquired = sem.tryAcquire(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw XamPulseException.rateLimited();
        }
        if (!acquired) {
            throw XamPulseException.rateLimited();
        }
        try {
            int timeout = props.getLimits().getQueryTimeoutSec();
            String sql = q.sql() + " SETTINGS SQL_xp_tenant = '" + tenant + "', max_execution_time = " + timeout
                    + ", max_rows_to_read = 2000000000";
            return gateway.query(sql, q.params(), timeout);
        } catch (SQLException e) {
            throw map(e);
        } finally {
            sem.release();
        }
    }

    /** Rows from one tenant's statement. */
    public record TenantRows(long tenantId, List<Map<String, Object>> rows) {}

    /** Runs the statement once per account in the scope (each strictly single-tenant) and returns the results per tenant. */
    public List<TenantRows> queryEach(QueryScope scope, java.util.function.Function<QueryScope, ScopedSql> builder) {
        java.util.ArrayList<TenantRows> out = new java.util.ArrayList<>();
        for (Long id : scope.accountIds()) {
            out.add(new TenantRows(id, query(builder.apply(scope.forTenant(id)))));
        }
        return out;
    }

    /** Package-visible for tests. */
    static void validate(ScopedSql q) {
        if (q == null) {
            throw new IllegalArgumentException("ScopedSql required");
        }
        if (q.tenantIds().isEmpty()) {
            throw new IllegalArgumentException("Query has no tenant");
        }
        if (q.tenantIds().size() != 1) {
            // The row policy keys on a single tenant per statement; multi-account views query per tenant and merge.
            throw new IllegalArgumentException("Run one tenant per statement");
        }
        String sql = q.sql();
        if (!sql.contains(ScopedSql.TENANT_MARKER)) {
            throw new IllegalArgumentException("Query lacks the tenant predicate");
        }
        String trimmed = sql.stripLeading();
        if (!trimmed.regionMatches(true, 0, "SELECT", 0, 6)) {
            throw new IllegalArgumentException("Only SELECT statements may run");
        }
        if (sql.indexOf(';') >= 0 || SET_STATEMENT.matcher(sql).find() || SETTINGS_CLAUSE.matcher(sql).find()) {
            throw new IllegalArgumentException("Statement contains a forbidden construct");
        }
        long placeholders = sql.chars().filter(c -> c == '?').count();
        if (placeholders != q.params().size()) {
            throw new IllegalArgumentException("Parameter count mismatch");
        }
        List<Long> tenants = q.tenantIds();
        for (int i = 0; i < tenants.size(); i++) {
            Object p = q.params().get(i);
            if (!(p instanceof Long l) || !l.equals(tenants.get(i))) {
                throw new IllegalArgumentException("Tenant ids must be the first parameters");
            }
        }
        if (!sql.toLowerCase(Locale.ROOT).contains("where tenant_id in (")) {
            throw new IllegalArgumentException("Tenant predicate must start the WHERE clause");
        }
    }

    private XamPulseException map(SQLException e) {
        String msg = String.valueOf(e.getMessage());
        // Never log parameters; they can contain user filter text.
        log.warn("XamPulse ClickHouse query failed: {}", msg.length() > 200 ? msg.substring(0, 200) : msg);
        String m = msg.toLowerCase(Locale.ROOT);
        if (m.contains("timeout") || m.contains("timed out") || m.contains("code: 159") || m.contains("code=159")) {
            return XamPulseException.timeout();
        }
        return XamPulseException.storeUnavailable();
    }
}
