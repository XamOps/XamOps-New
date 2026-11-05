package com.xammer.cloud.xampulse.service.query;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.dto.*;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Top-N value counts for whitelisted fields (and attribute keys). */
@Service("xpLogFacetService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class LogFacetService {

    private final QueryScopeFactory scopes;
    private final ClickHouseExecutor executor;
    private final TenantScopedQueryBuilder builder = new TenantScopedQueryBuilder();

    public LogFacetService(QueryScopeFactory scopes, ClickHouseExecutor executor) {
        this.scopes = scopes;
        this.executor = executor;
    }

    public XpLogFacetsResponse facets(ClientUserDetails user, XpLogFacetsRequest req) {
        QueryScope scope = scopes.build(user, req.scope());
        FilterAst filter = FilterDslParser.parse(req.query());
        int topN = req.topN() == null ? 10 : req.topN();
        List<XpFacet> facets = new ArrayList<>();
        for (String name : req.fields()) {
            LogField field;
            String attrKey = null;
            if (name != null && name.startsWith("attr.")) {
                field = LogField.ATTR;
                attrKey = name.substring(5);
            } else {
                field = LogField.fromName(name == null ? "" : name)
                        .orElseThrow(() -> XamPulseException.badFilter("Cannot facet on that field."));
            }
            final LogField f = field;
            final String key = attrKey;
            Map<String, Long> totals = new LinkedHashMap<>();
            for (ClickHouseExecutor.TenantRows tr : executor.queryEach(scope, s -> builder.facet(s, filter, f, key, topN))) {
                for (Map<String, Object> r : tr.rows()) {
                    totals.merge(Rows.s(r, "v"), Rows.l(r, "c"), Long::sum);
                }
            }
            List<XpFacetValue> values = totals.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(topN)
                    .map(e -> new XpFacetValue(e.getKey(), e.getValue()))
                    .toList();
            facets.add(new XpFacet(name, values));
        }
        // Counts come from a full aggregation (not a sample), so approx is false.
        return new XpLogFacetsResponse(false, facets);
    }
}
