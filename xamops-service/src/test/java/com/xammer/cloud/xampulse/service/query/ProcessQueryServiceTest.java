package com.xammer.cloud.xampulse.service.query;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.dto.XpProcessRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProcessQueryServiceTest {

    private static final long ACCOUNT = 100L;
    private static final String INSTANCE = "i-0abc";

    private ClientUserDetails user;
    private QueryScopeFactory scopes;

    @BeforeEach
    void setUp() {
        XamPulseProperties props = new XamPulseProperties();
        CloudAccountAccessService access = mock(CloudAccountAccessService.class);
        CloudAccount mine = new CloudAccount();
        mine.setId(ACCOUNT);
        when(access.requireAccess(any(), any())).thenReturn(mine);
        XamPulseAccessGuard guard = new XamPulseAccessGuard(access, mock(CloudAccountRepository.class));
        scopes = new QueryScopeFactory(guard, props);
        user = new ClientUserDetails("u", "x", List.of(new SimpleGrantedAuthority("ROLE_XAMOPS")), 1L, 10L, "t");
    }

    private ProcessQueryService serviceReturning(List<Map<String, Object>> rows) {
        ClickHouseExecutor executor = mock(ClickHouseExecutor.class);
        when(executor.queryEach(any(), any())).thenAnswer(inv -> {
            QueryScope scope = inv.getArgument(0);
            @SuppressWarnings("unchecked")
            Function<QueryScope, ScopedSql> fn = (Function<QueryScope, ScopedSql>) inv.getArgument(1);
            List<ClickHouseExecutor.TenantRows> out = new ArrayList<>();
            for (Long id : scope.accountIds()) {
                fn.apply(scope.forTenant(id)); // exercise the builder (tenant-scoping) as production would
                out.add(new ClickHouseExecutor.TenantRows(id, rows));
            }
            return out;
        });
        return new ProcessQueryService(scopes, executor);
    }

    private static Map<String, Object> row(String metric, String pid, String name, String user, double v) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("metric", metric);
        r.put("pid", pid);
        r.put("name", name);
        r.put("user", user);
        r.put("v", v);
        return r;
    }

    @Test
    void joinsTheTwoMetricsPerPidAndOrdersByCpuDescending() {
        List<Map<String, Object>> rows = List.of(
                row("process.cpu.utilization", "10", "java", "root", 0.20),
                row("process.memory.utilization", "10", "java", "root", 0.40),
                row("process.cpu.utilization", "11", "nginx", "www", 0.50),
                row("process.memory.utilization", "11", "nginx", "www", 0.10));
        List<XpProcessRow> out = serviceReturning(rows).topProcesses(user, ACCOUNT, INSTANCE, Instant.parse("2026-10-30T10:00:00Z"));

        assertThat(out).hasSize(2);
        XpProcessRow first = out.get(0);
        assertThat(first.pid()).isEqualTo(11L);
        assertThat(first.name()).isEqualTo("nginx");
        assertThat(first.user()).isEqualTo("www");
        assertThat(first.cpuPct()).isEqualTo(50.0);
        assertThat(first.memPct()).isEqualTo(10.0);
        XpProcessRow second = out.get(1);
        assertThat(second.pid()).isEqualTo(10L);
        assertThat(second.cpuPct()).isEqualTo(20.0);
        assertThat(second.memPct()).isEqualTo(40.0);
    }

    @Test
    void capsAtFiftyRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            rows.add(row("process.cpu.utilization", String.valueOf(1000 + i), "p" + i, "u", i / 1000.0));
        }
        List<XpProcessRow> out = serviceReturning(rows).topProcesses(user, ACCOUNT, INSTANCE, null);
        assertThat(out).hasSize(ProcessQueryService.MAX_ROWS);
        // Highest CPU first after the cap.
        assertThat(out.get(0).cpuPct()).isGreaterThan(out.get(out.size() - 1).cpuPct());
    }

    @Test
    void emptyWhenNoRows() {
        assertThat(serviceReturning(List.of()).topProcesses(user, ACCOUNT, INSTANCE, null)).isEmpty();
    }
}
