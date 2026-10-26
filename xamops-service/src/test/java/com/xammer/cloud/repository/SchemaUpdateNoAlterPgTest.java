package com.xammer.cloud.repository;

import com.xammer.cloud.domain.type.PgTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC 35 for xamops-service: a Hibernate schema-update dry run (script target only,
 * {@code ddl-auto=none}) against a database holding the four tables with today's column types
 * ({@code text}, NOT NULL on the two credentials) emits nothing for {@code json_data},
 * {@code schedule_json}, {@code encrypted_pat} or {@code encrypted_token}. {@code cached_data}
 * lacks {@code last_updated}, so the script must add it: proof that the tables were compared.
 *
 * <p>It also guards {@code vertical_workload_policy}, which is built here <strong>from its own
 * Flyway migration</strong> rather than from a hand-copied CREATE TABLE. Every deployed profile sets
 * {@code ddl-auto=update}, so Hibernate sees this table on every boot: if the entity and the
 * migration ever disagree about a column or about the unique key's name, Hibernate silently alters
 * the customer's table instead of the mismatch being caught here. {@code VerticalPolicyMigrationPgTest}
 * covers the Flyway side of the same file; this covers Hibernate's. {@code k8s_spot_interruption_event}
 * is guarded the same way, built from {@code V20261024_38__k8s_spot_interruption_event.sql}
 * ({@code SpotInterruptionMigrationPgTest} covers its Flyway side).
 */
@DataJpaTest
@ContextConfiguration(classes = JpaSliceTestConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LOBTEXT_TEST_JDBC_URL", matches = ".+")
class SchemaUpdateNoAlterPgTest {

    private static final List<String> GUARDED_COLUMNS =
            List.of("json_data", "schedule_json", "encrypted_pat", "encrypted_token");

    /** Tables that exist in full and must therefore produce no DDL at all. */
    private static final List<String> GUARDED_TABLES =
            List.of("vertical_workload_policy", "k8s_spot_interruption_event");

    private static final String VERTICAL_POLICY_MIGRATION =
            "/db/migration/V20261010_1__vertical_workload_policy.sql";

    private static final String SPOT_INTERRUPTION_MIGRATION =
            "/db/migration/V20261024_38__k8s_spot_interruption_event.sql";

    private static Path scriptDir;
    private static Path script;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PgTestSupport.requireLocal();
        String url = PgTestSupport.recreateDatabase("lobtext_x_schema");
        PgTestSupport.execute(url,
                "CREATE TABLE cached_data (cache_key varchar(255) NOT NULL PRIMARY KEY, json_data text)");
        PgTestSupport.execute(url,
                "CREATE TABLE cloudsitter_policies (id bigserial PRIMARY KEY, schedule_json text)");
        PgTestSupport.execute(url,
                "CREATE TABLE github_integration_config (id bigserial PRIMARY KEY, encrypted_pat text NOT NULL)");
        PgTestSupport.execute(url,
                "CREATE TABLE sonarqube_project (id bigserial PRIMARY KEY, encrypted_token text NOT NULL)");

        // vertical_workload_policy, exactly as Flyway would create it. Its two foreign keys need
        // their referenced primary keys to exist first; those two tables are left otherwise minimal,
        // so Hibernate will quite correctly emit DDL for them — the assertions below are scoped to
        // the table under test.
        PgTestSupport.execute(url, "CREATE TABLE client (id bigserial PRIMARY KEY, name varchar(255))");
        PgTestSupport.execute(url, "CREATE TABLE kubernetes_cluster_config ("
                + "id bigserial PRIMARY KEY, cluster_name varchar(255) NOT NULL)");
        MigrationScript.of(VERTICAL_POLICY_MIGRATION).applyTo(url);
        // k8s_spot_interruption_event, exactly as Flyway would create it (its FK needs only
        // kubernetes_cluster_config(id), created above).
        MigrationScript.of(SPOT_INTERRUPTION_MIGRATION).applyTo(url);

        try {
            scriptDir = Files.createTempDirectory("lobtext-schema-update-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        script = scriptDir.resolve("update.sql");
        PgTestSupport.springProperties(url, "none").forEach((k, v) -> registry.add(k, () -> v));
        registry.add("spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action", () -> "update");
        registry.add("spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-target",
                () -> script.toString());
    }

    @AfterAll
    static void deleteScript() throws IOException {
        if (script != null) {
            Files.deleteIfExists(script);
            Files.deleteIfExists(scriptDir);
        }
    }

    @Test
    void schemaUpdateAddsTheMissingColumnButNeverTouchesTheGuardedColumns() throws IOException {
        assertThat(script).as("Hibernate wrote the update script").exists();
        List<String> statements = Files.readAllLines(script, StandardCharsets.UTF_8).stream()
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .toList();

        statements.stream()
                .filter(s -> s.matches(".*\\b(cached_data|cloudsitter_policies|github_integration_config|sonarqube_project)\\b.*"))
                .forEach(s -> LoggerFactory.getLogger(SchemaUpdateNoAlterPgTest.class).info("update script: {}", s));

        assertThat(statements).as("positive control: cached_data.last_updated is added")
                .anySatisfy(s -> assertThat(s).contains("alter table", "cached_data", "add column", "last_updated"));
        for (String column : GUARDED_COLUMNS) {
            assertThat(statements).as(column).filteredOn(s -> s.contains(column)).isEmpty();
        }
    }

    @Test
    void schemaUpdateIsANoOpForTheTablesTheMigrationsOwn() throws IOException {
        assertThat(script).as("Hibernate wrote the update script").exists();
        List<String> statements = Files.readAllLines(script, StandardCharsets.UTF_8).stream()
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .toList();

        for (String table : GUARDED_TABLES) {
            // Any statement at all is a finding: the table was created from its migration, so an
            // ALTER means the entity and the migration disagree, and ddl-auto=update would quietly
            // reshape a customer's table on the next boot.
            assertThat(statements)
                    .as("%s was created from its own migration, so Hibernate must have nothing to do",
                            table)
                    .filteredOn(s -> s.contains(table))
                    .isEmpty();
        }
    }
}
