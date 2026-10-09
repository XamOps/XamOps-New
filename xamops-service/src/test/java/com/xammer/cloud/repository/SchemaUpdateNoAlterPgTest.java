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
 */
@DataJpaTest
@ContextConfiguration(classes = JpaSliceTestConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LOBTEXT_TEST_JDBC_URL", matches = ".+")
class SchemaUpdateNoAlterPgTest {

    private static final List<String> GUARDED_COLUMNS =
            List.of("json_data", "schedule_json", "encrypted_pat", "encrypted_token");

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
}
