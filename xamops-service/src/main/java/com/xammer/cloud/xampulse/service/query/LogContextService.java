package com.xammer.cloud.xampulse.service.query;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.dto.XpLogContextResponse;
import com.xammer.cloud.xampulse.dto.XpLogRow;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Lines around one log row. The row id is re-authorised on every call. */
@Service("xpLogContextService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class LogContextService {

    private final XamPulseAccessGuard guard;
    private final ClickHouseExecutor executor;
    private final TenantScopedQueryBuilder builder = new TenantScopedQueryBuilder();

    public LogContextService(XamPulseAccessGuard guard, ClickHouseExecutor executor) {
        this.guard = guard;
        this.executor = executor;
    }

    public XpLogContextResponse context(ClientUserDetails user, Long accountId, String id, int before, int after, boolean sameSource) {
        LogRowIdCodec.RowRef ref = LogRowIdCodec.decode(id);
        guard.require(user, accountId);
        if (ref.tenantId() != accountId) {
            // An id minted for another tenant, even one the caller can see, is not valid for this account.
            throw XamPulseException.notFound();
        }
        List<Map<String, Object>> anchorRows = executor.query(builder.anchor(ref.tenantId(), ref.tsMs(), ref.instanceId(), ref.bodyHash()));
        if (anchorRows.isEmpty()) {
            throw XamPulseException.notFound();
        }
        XpLogRow anchor = Rows.logRow(anchorRows.get(0));
        List<XpLogRow> older = fetch(builder.context(ref.tenantId(), ref.tsMs(), ref.bodyHash(), anchor.instanceId(), anchor.source(),
                true, sameSource, Math.max(0, Math.min(before, 100))));
        Collections.reverse(older);
        List<XpLogRow> newer = fetch(builder.context(ref.tenantId(), ref.tsMs(), ref.bodyHash(), anchor.instanceId(), anchor.source(),
                false, sameSource, Math.max(0, Math.min(after, 100))));
        return new XpLogContextResponse(older, anchor, newer);
    }

    private List<XpLogRow> fetch(ScopedSql sql) {
        List<XpLogRow> out = new ArrayList<>();
        for (Map<String, Object> r : executor.query(sql)) {
            out.add(Rows.logRow(r));
        }
        return out;
    }
}
