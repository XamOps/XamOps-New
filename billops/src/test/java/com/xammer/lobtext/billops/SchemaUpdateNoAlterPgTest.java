package com.xammer.lobtext.billops;

import com.xammer.cloud.domain.type.PgTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
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
 * AC 35 for billops: a Hibernate schema-update dry run (script target only, {@code ddl-auto=none})
 * against a database that holds {@code cached_data} with today's column types emits nothing for
 * {@code json_data}. The table lacks {@code last_updated}, so the script must add it: proof that the
 * dry run really compared this table.
 */
@DataJpaTest
@ContextConfiguration(classes = LobTextBillopsPgConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("lobtext-pg")
@EnabledIfEnvironmentVariable(named = "LOBTEXT_TEST_JDBC_URL", matches = ".+")
class SchemaUpdateNoAlterPgTest {

    private static Path scriptDir;
    private static Path script;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PgTestSupport.requireLocal();
        String url = PgTestSupport.recreateDatabase("lobtext_b_schema");
        PgTestSupport.execute(url,
                "CREATE TABLE cached_data (cache_key varchar(255) NOT NULL PRIMARY KEY, json_data text)");
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
    void schemaUpdateAddsTheMissingColumnButNeverTouchesJsonData() throws IOException {
        assertThat(script).as("Hibernate wrote the update script").exists();
        List<String> statements = Files.readAllLines(script, StandardCharsets.UTF_8).stream()
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .toList();

        statements.stream()
                .filter(s -> s.matches(".*\\bcached_data\\b.*"))
                .forEach(s -> LoggerFactory.getLogger(SchemaUpdateNoAlterPgTest.class).info("update script: {}", s));

        assertThat(statements).as("positive control: cached_data.last_updated is added")
                .anySatisfy(s -> assertThat(s).contains("alter table", "cached_data", "add column", "last_updated"));
        assertThat(statements).filteredOn(s -> s.contains("json_data")).isEmpty();
    }
}
