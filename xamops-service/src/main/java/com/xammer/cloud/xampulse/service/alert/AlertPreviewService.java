package com.xammer.cloud.xampulse.service.alert;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.dto.XpAlertPreview;
import com.xammer.cloud.xampulse.dto.XpAlertRule;
import com.xammer.cloud.xampulse.dto.XpCondition;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.service.query.ClickHouseExecutor;
import com.xammer.cloud.xampulse.service.query.FilterAst;
import com.xammer.cloud.xampulse.service.query.QueryScope;
import com.xammer.cloud.xampulse.service.query.QueryScopeFactory;
import com.xammer.cloud.xampulse.service.query.Rows;
import com.xammer.cloud.xampulse.service.query.TenantScopedQueryBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "What would this rule have done over the last 24 hours?" Supported for metric-threshold and log-count rules.
 * Log-count preview uses tumbling windows over the combined scope (all selected instances together).
 */
@Service("xpAlertPreviewService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class AlertPreviewService {

    private final QueryScopeFactory scopes;
    private final ClickHouseExecutor executor;
    private final RuleCompiler compiler;
    private final TenantScopedQueryBuilder builder = new TenantScopedQueryBuilder();

    public AlertPreviewService(QueryScopeFactory scopes, ClickHouseExecutor executor, RuleCompiler compiler) {
        this.scopes = scopes;
        this.executor = executor;
        this.compiler = compiler;
    }

    public XpAlertPreview preview(ClientUserDetails user, XpAlertRule rule) {
        compiler.validate(rule);
        XpCondition c = rule.condition();
        Instant now = Instant.now();
        Long account = rule.scope().accountIds().get(0);
        QueryScope scope = scopes.build(user, List.of(account), now.minus(Duration.ofHours(24)), now, null,
                rule.scope().instanceIds(), null, null);
        Duration hold = RuleCompiler.hold(c);
        Map<String, List<AlertPreviewEngine.Point>> series = new LinkedHashMap<>();
        switch (c.type()) {
            case "METRIC_THRESHOLD" -> {
                var spec = RuleCompiler.spec(c, c.metric());
                var plan = TenantScopedQueryBuilder.planMetric(scope, null);
                for (Map<String, Object> r : executor.query(builder.metricSeries(scope.forTenant(account), spec, plan))) {
                    Double v = Rows.dOrNull(r, "v");
                    if (v != null) {
                        series.computeIfAbsent(Rows.s(r, "g0"), k -> new ArrayList<>())
                                .add(new AlertPreviewEngine.Point(Instant.ofEpochSecond(Rows.l(r, "t")), v));
                    }
                }
            }
            case "LOG_COUNT" -> {
                FilterAst filter = RuleCompiler.query(c);
                long bucket = DurationSpec.parse(c.window(), "window").toSeconds();
                Map<Long, Double> totals = new LinkedHashMap<>();
                for (Map<String, Object> r : executor.query(builder.histogram(scope.forTenant(account), filter, bucket))) {
                    totals.merge(Rows.l(r, "t"), (double) Rows.l(r, "c"), Double::sum);
                }
                List<AlertPreviewEngine.Point> pts = new ArrayList<>();
                totals.forEach((t, v) -> pts.add(new AlertPreviewEngine.Point(Instant.ofEpochSecond(t), v)));
                series.put("all selected instances", pts);
            }
            default -> throw XamPulseException.invalid("Preview is not available for this condition type yet.");
        }
        return AlertPreviewEngine.run(series, c.op(), c.value(), hold);
    }
}
