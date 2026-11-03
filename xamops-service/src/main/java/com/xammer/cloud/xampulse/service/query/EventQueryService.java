package com.xammer.cloud.xampulse.service.query;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.dto.XpEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Platform-written events (deploys, EC2 state changes, alerts, anomalies) for the timeline. */
@Service("xpEventQueryService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class EventQueryService {

    public static final Set<String> KINDS = Set.of("DEPLOY", "EC2_STATE", "ALERT", "ANOMALY", "AGENT", "CONFIG");

    private final QueryScopeFactory scopes;
    private final ClickHouseExecutor executor;
    private final TenantScopedQueryBuilder builder = new TenantScopedQueryBuilder();

    public EventQueryService(QueryScopeFactory scopes, ClickHouseExecutor executor) {
        this.scopes = scopes;
        this.executor = executor;
    }

    public List<XpEvent> events(ClientUserDetails user, List<Long> accountIds, Instant from, Instant to, List<String> types) {
        QueryScope scope = scopes.build(user, accountIds, from, to);
        return events(scope, types);
    }

    public List<XpEvent> events(QueryScope scope, List<String> types) {
        List<String> kinds = types == null ? List.of() : types.stream().filter(KINDS::contains).toList();
        List<XpEvent> out = new ArrayList<>();
        for (ClickHouseExecutor.TenantRows tr : executor.queryEach(scope, s -> builder.events(s, kinds, 500))) {
            for (Map<String, Object> r : tr.rows()) {
                out.add(new XpEvent(Instant.ofEpochMilli(Rows.l(r, "ts_ms")), Rows.s(r, "kind"), tr.tenantId(),
                        Rows.s(r, "resource"), Rows.s(r, "title"), Rows.s(r, "detail")));
            }
        }
        out.sort(Comparator.comparing(XpEvent::ts).reversed());
        return out.size() > 500 ? out.subList(0, 500) : out;
    }
}
