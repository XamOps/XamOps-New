package com.xammer.cloud.autospotting.pg;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * PostgreSQL harness for the AutoSpotting migration tests (design-core.md §10, design-ux-ops.md
 * §9.1). Tests that use it carry {@code @Tag("containers")}, which the default {@code mvn test}
 * leaves out; run them with {@code -Dgroups=containers -Dsurefire.excludedGroups=}.
 *
 * <p>Two modes, each in a database of its own (created per call, dropped on {@link Database#close()}):
 * <ul>
 *   <li>{@link #emptySchema()}: an empty database, then only the AutoSpotting migrations (those after
 *       {@link #BASELINE_VERSION} whose name contains "autospotting"). Proves they are self-contained.</li>
 *   <li>{@link #hibernateBase(Fixture)}: Hibernate builds the shared base schema from the entities
 *       ({@code ddl-auto=create}, Flyway off), the fixture inserts rows, then Flyway runs with
 *       {@code baselineOnMigrate=true} and {@code baselineVersion=20260929.2}, so exactly the
 *       migrations after the baseline apply ({@code V20261006_1} and later). This is how prod looks:
 *       tables made by ddl-auto, then the new migrations.</li>
 * </ul>
 *
 * <p>The server is a Testcontainers {@value #IMAGE} container, started once per JVM. On a machine
 * without Docker, {@code -Dpg.harness.admin-url=jdbc:postgresql://127.0.0.1:<port>/postgres} (plus
 * {@code pg.harness.username} and {@code pg.harness.password}) points the harness at a throwaway
 * local server instead. Only localhost is accepted, because the harness creates and drops databases.
 */
public final class PgHarness {

    /** Prod's engine (FIXES-SUMMARY-2026-06-08.md:301). V-TST-01 confirms it with SHOW server_version. */
    public static final String IMAGE = "postgres:15.13";

    /** Newest migration on main before the AutoSpotting program. */
    public static final String BASELINE_VERSION = "20260929.2";

    public static final String ADMIN_URL_PROPERTY = "pg.harness.admin-url";
    public static final String USERNAME_PROPERTY = "pg.harness.username";
    public static final String PASSWORD_PROPERTY = "pg.harness.password";

    private static final String MIGRATION_LOCATION = "db/migration";
    private static final Pattern VERSIONED_MIGRATION = Pattern.compile("^V([0-9]+(?:[._][0-9]+)*)__(.+)\\.sql$");
    private static final Pattern LOCAL_JDBC_URL =
            Pattern.compile("^jdbc:postgresql://(localhost|127\\.0\\.0\\.1|\\[::1]):([0-9]+)/[^?]*(\\?.*)?$");
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private static Server server;

    private PgHarness() {
    }

    /** Inserts rows (or changes the base schema) after Hibernate built it and before Flyway runs. */
    @FunctionalInterface
    public interface Fixture {
        void apply(JdbcTemplate jdbc) throws Exception;
    }

    /** A versioned migration file under {@code db/migration}. */
    public record Migration(MigrationVersion version, String description, String fileName, Resource resource) {

        public boolean isAutoSpotting() {
            return description.toLowerCase(Locale.ROOT).contains("autospotting");
        }

        public boolean isAfterBaseline() {
            return version.isNewerThan(BASELINE_VERSION);
        }

        public String sql() {
            try (InputStream in = resource.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** The server every database is created on. */
    public record Server(String host, int port, String username, String password, String description) {

        String jdbcUrl(String database) {
            return "jdbc:postgresql://" + host + ":" + port + "/" + database;
        }
    }

    // ── modes ────────────────────────────────────────────────────────────────

    public static Database emptySchema() {
        Database db = Database.create("empty");
        try {
            Path location = Files.createTempDirectory("pg-harness-autospotting-");
            try {
                for (Migration migration : autoSpottingMigrations()) {
                    try (InputStream in = migration.resource().getInputStream()) {
                        Files.copy(in, location.resolve(migration.fileName()), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                db.migrate(Flyway.configure()
                        .locations("filesystem:" + location.toAbsolutePath()));
            } finally {
                deleteRecursively(location);
            }
            return db;
        } catch (IOException | RuntimeException e) {
            db.close();
            throw e instanceof IOException io ? new UncheckedIOException(io) : (RuntimeException) e;
        }
    }

    public static Database hibernateBase(Fixture fixture) {
        Database db = Database.create("hibernate");
        try {
            buildSchemaWithHibernate(db);
            fixture.apply(db.jdbc());
            db.migrate(Flyway.configure()
                    .locations("classpath:" + MIGRATION_LOCATION)
                    .baselineOnMigrate(true)
                    .baselineVersion(BASELINE_VERSION));
            return db;
        } catch (Exception e) {
            db.close();
            throw e instanceof RuntimeException re ? re : new IllegalStateException("hibernateBase fixture failed", e);
        }
    }

    // ── migrations on the classpath ──────────────────────────────────────────

    /** Every versioned migration, in version order. */
    public static List<Migration> migrations() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:" + MIGRATION_LOCATION + "/*.sql");
            Map<String, Migration> byFile = new TreeMap<>();
            for (Resource resource : resources) {
                String name = resource.getFilename();
                Matcher m = name == null ? null : VERSIONED_MIGRATION.matcher(name);
                if (m != null && m.matches()) {
                    byFile.putIfAbsent(name, new Migration(
                            MigrationVersion.fromVersion(m.group(1).replace('_', '.')), m.group(2), name, resource));
                }
            }
            List<Migration> sorted = new ArrayList<>(byFile.values());
            sorted.sort(Comparator.comparing(Migration::version));
            return sorted;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** What {@link #hibernateBase(Fixture)} applies: every migration after the baseline. */
    public static List<Migration> migrationsAfterBaseline() {
        return migrations().stream().filter(Migration::isAfterBaseline).toList();
    }

    /** What {@link #emptySchema()} applies: the AutoSpotting migrations after the baseline. */
    public static List<Migration> autoSpottingMigrations() {
        return migrationsAfterBaseline().stream().filter(Migration::isAutoSpotting).toList();
    }

    public static Migration migration(String version) {
        return migrations().stream()
                .filter(m -> m.version().getVersion().equals(version))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No migration with version " + version));
    }

    // ── fixture helpers ──────────────────────────────────────────────────────

    /**
     * Inserts one row, filling every other NOT NULL column that has no default with a neutral
     * value (0, false, '', now), so a fixture names only the columns it cares about and survives
     * new entity fields. Foreign keys and ids must be given explicitly.
     */
    public static void insertRow(JdbcTemplate jdbc, String table, Map<String, ?> values) {
        Map<String, Object> row = new LinkedHashMap<>(values);
        List<Map<String, Object>> required = jdbc.queryForList(
                "SELECT column_name, data_type FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = ? "
                        + "AND is_nullable = 'NO' AND column_default IS NULL AND is_identity = 'NO' "
                        + "ORDER BY ordinal_position", table);
        if (required.isEmpty() && !tableExists(jdbc, table)) {
            throw new IllegalArgumentException("No table " + table);
        }
        for (Map<String, Object> column : required) {
            String name = (String) column.get("column_name");
            if (!row.containsKey(name)) {
                row.put(name, neutralValue(table, name, (String) column.get("data_type")));
            }
        }
        String columns = String.join(", ", row.keySet());
        String placeholders = String.join(", ", row.keySet().stream().map(c -> "?").toList());
        jdbc.update("INSERT INTO " + table + " (" + columns + ") VALUES (" + placeholders + ")", row.values().toArray());
    }

    public static boolean tableExists(JdbcTemplate jdbc, String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, table));
    }

    private static Object neutralValue(String table, String column, String dataType) {
        return switch (dataType) {
            case "character varying", "text", "character" -> "";
            case "smallint", "integer", "bigint", "oid" -> 0L;
            case "numeric", "double precision", "real" -> 0.0;
            case "boolean" -> false;
            case "date" -> java.sql.Date.valueOf(java.time.LocalDate.of(2026, 1, 1));
            case "timestamp without time zone", "timestamp with time zone" ->
                    java.sql.Timestamp.valueOf(java.time.LocalDateTime.of(2026, 1, 1, 0, 0));
            case "bytea" -> new byte[0];
            default -> throw new IllegalArgumentException("Give " + table + "." + column + " (" + dataType
                    + ") explicitly; the harness has no neutral value for that type");
        };
    }

    // ── server ───────────────────────────────────────────────────────────────

    public static synchronized Server server() {
        if (server == null) {
            String adminUrl = System.getProperty(ADMIN_URL_PROPERTY);
            server = adminUrl == null || adminUrl.isBlank() ? startContainer() : localServer(adminUrl);
        }
        return server;
    }

    private static Server startContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(DockerImageName.parse(IMAGE));
        container.start();
        Runtime.getRuntime().addShutdownHook(new Thread(container::stop, "pg-harness-stop"));
        return new Server(container.getHost(), container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                container.getUsername(), container.getPassword(), "Testcontainers " + IMAGE);
    }

    private static Server localServer(String adminUrl) {
        Matcher m = LOCAL_JDBC_URL.matcher(adminUrl);
        if (!m.matches() || !LOCAL_HOSTS.contains(m.group(1))) {
            throw new IllegalStateException(ADMIN_URL_PROPERTY + " must be a jdbc:postgresql URL on localhost: "
                    + "the harness creates and drops databases there");
        }
        String username = System.getProperty(USERNAME_PROPERTY);
        if (username == null || username.isBlank()) {
            throw new IllegalStateException(USERNAME_PROPERTY + " is required with " + ADMIN_URL_PROPERTY);
        }
        return new Server(m.group(1), Integer.parseInt(m.group(2)), username,
                System.getProperty(PASSWORD_PROPERTY, ""), "local server " + m.group(1) + ":" + m.group(2));
    }

    // ── Hibernate base schema ────────────────────────────────────────────────

    private static void buildSchemaWithHibernate(Database db) {
        Map<String, Object> properties = Map.of(
                "spring.datasource.url", db.jdbcUrl(),
                "spring.datasource.username", db.username(),
                "spring.datasource.password", db.password(),
                "spring.jpa.hibernate.ddl-auto", "create",
                "spring.jpa.open-in-view", "false",
                "spring.flyway.enabled", "false");
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("pg-harness", properties));
            context.register(HibernateBaseSchema.class);
            context.refresh(); // the EntityManagerFactory runs the schema export on startup
        }
    }

    /** Entities only: no repositories, no Flyway, no application beans. */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            TransactionAutoConfiguration.class})
    @EntityScan(basePackages = "com.xammer.cloud.domain")
    static class HibernateBaseSchema {
    }

    // ── database ─────────────────────────────────────────────────────────────

    /** One database on the harness server. Closing it drops it. */
    public static final class Database implements AutoCloseable {

        private final Server server;
        private final String name;
        private final JdbcTemplate jdbc;
        private List<MigrationInfo> applied = List.of();

        private Database(Server server, String name) {
            this.server = server;
            this.name = name;
            this.jdbc = new JdbcTemplate(new DriverManagerDataSource(server.jdbcUrl(name), server.username(),
                    server.password()));
        }

        static Database create(String mode) {
            Server server = PgHarness.server();
            String name = "as_" + mode + "_" + UUID.randomUUID().toString().substring(0, 8);
            admin(server, "CREATE DATABASE " + name);
            return new Database(server, name);
        }

        void migrate(org.flywaydb.core.api.configuration.FluentConfiguration configuration) {
            Flyway flyway = configuration
                    .dataSource(jdbcUrl(), username(), password())
                    .cleanDisabled(true)
                    .load();
            flyway.migrate();
            applied = List.copyOf(Arrays.asList(flyway.info().applied()));
        }

        public String name() {
            return name;
        }

        public String jdbcUrl() {
            return server.jdbcUrl(name);
        }

        public String username() {
            return server.username();
        }

        public String password() {
            return server.password();
        }

        public Server server() {
            return server;
        }

        public JdbcTemplate jdbc() {
            return jdbc;
        }

        public Connection connect() throws SQLException {
            return DriverManager.getConnection(jdbcUrl(), username(), password());
        }

        /** Flyway's schema history after the harness migrated, baseline marker included. */
        public List<MigrationInfo> applied() {
            return applied;
        }

        /** Versions of the migrations Flyway executed, in order, without the baseline marker. */
        public List<String> appliedVersions() {
            return applied.stream()
                    .filter(info -> !info.getType().isBaseline())
                    .map(info -> info.getVersion().getVersion())
                    .toList();
        }

        public Optional<String> baselineVersion() {
            return applied.stream()
                    .filter(info -> info.getType().isBaseline())
                    .map(info -> info.getVersion().getVersion())
                    .findFirst();
        }

        @Override
        public void close() {
            admin(server, "DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
        }

        private static void admin(Server server, String sql) {
            try (Connection connection = DriverManager.getConnection(server.jdbcUrl("postgres"), server.username(),
                    server.password()); Statement statement = connection.createStatement()) {
                statement.execute(sql);
            } catch (SQLException e) {
                throw new IllegalStateException("PG harness: " + sql + " failed: " + e.getMessage(), e);
            }
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
