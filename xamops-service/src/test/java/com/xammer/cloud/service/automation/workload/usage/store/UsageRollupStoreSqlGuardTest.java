package com.xammer.cloud.service.automation.workload.usage.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Tenant scoping, enforced over the SQL text itself: every {@code SELECT}/{@code UPDATE}/
 * {@code DELETE} against a {@code k8s_workload_*} table that {@link UsageRollupStore} holds must
 * filter on both {@code client_id = ?} and {@code cluster_config_id = ?}, unless it is on the
 * explicit allow-list below. A new query added without the tenant filter fails here, by name.
 */
class UsageRollupStoreSqlGuardTest {

    /**
     * Statements that legitimately do not filter on the tenant. Each is internal to the rollup and
     * maintenance jobs; none of their results is returned to a caller as tenant data.
     */
    private static final Map<String, String> ALLOW_LIST = Map.of(
            // Cluster-scoped job reads: the rows read are written back under the same cluster.
            "SELECT_SAMPLES", "readSamples: hourly job input, scoped to one cluster",
            "SELECT_HOURLY", "readHourly: daily job input, scoped to one cluster",
            // Cluster-scoped job write: ids come from that cluster's own identity rows.
            "UPDATE_LAST_SEEN", "touchLastSeen: hourly job bookkeeping, scoped to one cluster",
            // Job discovery: returns cluster ids only, no tenant data.
            "SELECT_CLUSTERS_WITH_SAMPLES", "clustersWithSamples: which clusters the hourly job visits",
            "SELECT_CLUSTERS_WITH_HOURLY", "clustersWithHourly: which clusters the daily job visits",
            // Maintenance: a whole-partition verification count, returns a number only.
            "COUNT_UNROLLED_DAYS_TEMPLATE", "countUnrolledDays: drop verification over one partition",
            // Catalog / DDL: no tenant rows at all.
            "LIST_PARTITIONS", "listPartitions: pg_inherits catalog read",
            "DROP_PARTITION_TEMPLATE", "dropPartition: DDL on a name UsagePartitions.parse accepted");

    /** The cluster-scoped internal reads must at least be cluster-scoped. */
    private static final Set<String> CLUSTER_SCOPED = Set.of("SELECT_SAMPLES", "SELECT_HOURLY", "UPDATE_LAST_SEEN");

    @Test
    @DisplayName("every tenant-facing SELECT/UPDATE/DELETE filters on client_id AND cluster_config_id")
    void everyTenantStatementIsTenantScoped() throws IllegalAccessException {
        Map<String, String> statements = sqlConstants();
        int checked = 0;
        for (Map.Entry<String, String> entry : statements.entrySet()) {
            String name = entry.getKey();
            String sql = entry.getValue();
            if (!readsOrChangesUsageRows(sql) || ALLOW_LIST.containsKey(name)) {
                continue;
            }
            checked++;
            assertThat(sql).as("%s must filter on the tenant", name)
                    .contains("client_id = ?")
                    .contains("cluster_config_id = ?");
        }
        assertThat(checked)
                .as("the tenant read API and the identity lookup are checked, so the guard is not vacuous")
                .isGreaterThanOrEqualTo(3);
        assertThat(statements).containsKeys("FIND_HOURLY", "FIND_DAILY", "SELECT_IDENTITY_IDS");
    }

    @Test
    @DisplayName("the read API also joins the identity on both tenant ids")
    void theReadApiJoinsOnTheTenant() throws IllegalAccessException {
        for (String name : List.of("FIND_HOURLY", "FIND_DAILY")) {
            String sql = sqlConstants().get(name);
            assertThat(sql).as(name)
                    .contains("i.id = f.workload_id")
                    .contains("i.client_id = f.client_id")
                    .contains("i.cluster_config_id = f.cluster_config_id")
                    .contains("f.client_id = ?")
                    .contains("f.cluster_config_id = ?");
        }
    }

    @Test
    @DisplayName("every allow-list entry names a real constant, and the internal reads are cluster-scoped")
    void theAllowListIsCurrent() throws IllegalAccessException {
        Map<String, String> statements = sqlConstants();
        assertThat(statements.keySet()).as("a stale allow-list entry would hide nothing but mislead")
                .containsAll(ALLOW_LIST.keySet());
        for (String name : CLUSTER_SCOPED) {
            assertThat(statements.get(name)).as(name).contains("cluster_config_id = ?");
        }
    }

    @Test
    @DisplayName("findHourly/findDaily reject a non-positive id without touching the DataSource")
    void nonPositiveIdsAreRejected() {
        DataSource dataSource = mock(DataSource.class);
        UsageRollupStore store = new UsageRollupStore(dataSource);
        Instant from = Instant.parse("2026-10-24T00:00:00Z");
        Instant to = Instant.parse("2026-10-25T00:00:00Z");

        assertThatThrownBy(() -> store.findHourly(0, 1, from, to)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.findHourly(1, 0, from, to)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.findHourly(-1, 1, from, to)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.findDaily(0, 1, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.findDaily(1, 0, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.upsertIdentities(0, 1, List.of(), from))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(dataSource);
    }

    @Test
    @DisplayName("dropPartition and countUnrolledDays refuse names that are not ours, before any SQL")
    void foreignPartitionNamesAreRefused() {
        DataSource dataSource = mock(DataSource.class);
        UsageRollupStore store = new UsageRollupStore(dataSource);

        assertThatThrownBy(() -> store.dropPartition("k8s_workload_usage_hourly_old"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.dropPartition("client"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.countUnrolledDays("k8s_workload_usage_daily_y2026m10"))
                .as("only an hourly partition can be verified")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.listPartitions("client"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(dataSource);
    }

    /** Every {@code static final String} constant of the store, by field name. */
    private static Map<String, String> sqlConstants() throws IllegalAccessException {
        Map<String, String> constants = new LinkedHashMap<>();
        for (Field field : UsageRollupStore.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers) && field.getType() == String.class) {
                field.setAccessible(true);
                constants.put(field.getName(), (String) field.get(null));
            }
        }
        return constants;
    }

    /** A SELECT/UPDATE/DELETE (or WITH) statement that touches a k8s_workload_* table. */
    private static boolean readsOrChangesUsageRows(String sql) {
        String head = sql.strip().toUpperCase(Locale.ROOT);
        boolean leading = head.startsWith("SELECT") || head.startsWith("UPDATE")
                || head.startsWith("DELETE") || head.startsWith("WITH");
        return leading && sql.contains("k8s_workload_");
    }
}
