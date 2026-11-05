package com.xammer.cloud.xampulse.service.platform;

import com.xammer.cloud.xampulse.config.ClickHouseConnections;
import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.domain.XpMigrationLog;
import com.xammer.cloud.xampulse.repository.XpMigrationLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies the ClickHouse schema scripts ({@code xampulse/clickhouse/V###__name.sql}) once each, in order, recording
 * them in ClickHouse {@code xp_schema_version} and mirroring into Postgres {@code xp_migration_log}.
 * Flyway has no first-party ClickHouse support, so this is a deliberately small runner.
 *
 * <p>Scripts are immutable once applied: a changed checksum is reported, never re-run. {@code {{REPLICATED}}} is
 * replaced by {@code Replicated} when {@code xampulse.clickhouse.replicated=true}, else by nothing.
 */
@Service("xpClickHouseMigrationRunner")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class ClickHouseMigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseMigrationRunner.class);
    private static final Pattern FILE = Pattern.compile("^V(\\d+)__([A-Za-z0-9_]+)\\.sql$");

    public record Script(String version, String name, String content) {}

    public record Result(List<String> applied, List<String> alreadyApplied, List<String> checksumChanged) {}

    private final ClickHouseDdl ddl;
    private final XpMigrationLogRepository mirror;
    private final boolean replicated;

    @org.springframework.beans.factory.annotation.Autowired
    public ClickHouseMigrationRunner(ClickHouseConnections connections, XamPulseProperties props, XpMigrationLogRepository mirror) {
        this(new JdbcClickHouseDdl(connections), mirror, props.getClickhouse().isReplicated());
    }

    public ClickHouseMigrationRunner(ClickHouseDdl ddl, XpMigrationLogRepository mirror, boolean replicated) {
        this.ddl = ddl;
        this.mirror = mirror;
        this.replicated = replicated;
    }

    /** Runs after startup so a down ClickHouse never blocks the application from booting. */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            Result r = run(loadClasspathScripts());
            log.info("XamPulse ClickHouse schema: applied={} unchanged={} changedSinceApplied={}",
                    r.applied(), r.alreadyApplied().size(), r.checksumChanged());
        } catch (Exception e) {
            log.error("XamPulse ClickHouse schema could not be applied; analytics endpoints will report the store as unavailable. cause={}",
                    e.getClass().getSimpleName());
        }
    }

    public Result run(List<Script> scripts) throws SQLException {
        ddl.execute(substitute("CREATE TABLE IF NOT EXISTS xp_schema_version (version String, checksum String, "
                + "applied_at DateTime DEFAULT now()) ENGINE = {{REPLICATED}}MergeTree ORDER BY version", replicated));
        Map<String, String> done = ddl.applied();
        List<String> applied = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (Script s : scripts.stream().sorted(Comparator.comparingInt(x -> Integer.parseInt(x.version().substring(1)))).toList()) {
            if (done.containsKey(s.version())) {
                skipped.add(s.version());
                if (!done.get(s.version()).equals(sha256(s.content()))) {
                    changed.add(s.version());
                }
                continue;
            }
            for (String stmt : splitStatements(substitute(s.content(), replicated))) {
                ddl.execute(stmt);
            }
            String sum = sha256(s.content());
            ddl.markApplied(s.version(), sum);
            mirrorToPostgres(s.version(), sum);
            applied.add(s.version());
        }
        return new Result(applied, skipped, changed);
    }

    private void mirrorToPostgres(String version, String checksum) {
        if (mirror == null) {
            return;
        }
        try {
            if (mirror.findByVersion(version).isEmpty()) {
                XpMigrationLog m = new XpMigrationLog();
                m.setVersion(version);
                m.setChecksum(checksum);
                m.setAppliedAt(Instant.now());
                mirror.save(m);
            }
        } catch (RuntimeException e) {
            log.warn("Could not mirror ClickHouse migration {} into Postgres: {}", version, e.getClass().getSimpleName());
        }
    }

    public static String substitute(String script, boolean replicated) {
        return script.replace("{{REPLICATED}}", replicated ? "Replicated" : "");
    }

    /** Splits on statement-ending semicolons outside quotes; drops comment-only lines. */
    public static List<String> splitStatements(String script) {
        StringBuilder cleaned = new StringBuilder();
        for (String line : script.split("\n", -1)) {
            String t = line.stripLeading();
            if (!t.startsWith("--")) {
                cleaned.append(line).append('\n');
            }
        }
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        String s = cleaned.toString();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' && (i == 0 || s.charAt(i - 1) != '\\')) {
                inQuote = !inQuote;
            }
            if (c == ';' && !inQuote) {
                String stmt = cur.toString().trim();
                if (!stmt.isEmpty()) {
                    out.add(stmt);
                }
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        String tail = cur.toString().trim();
        if (!tail.isEmpty()) {
            out.add(tail);
        }
        return out;
    }

    public static List<Script> loadClasspathScripts() throws IOException {
        List<Script> out = new ArrayList<>();
        for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath*:xampulse/clickhouse/V*.sql")) {
            String name = r.getFilename();
            Matcher m = name == null ? null : FILE.matcher(name);
            if (m == null || !m.matches()) {
                continue;
            }
            out.add(new Script("V" + m.group(1), m.group(2), new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8)));
        }
        return out;
    }

    static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
