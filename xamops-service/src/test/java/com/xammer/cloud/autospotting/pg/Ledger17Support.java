package com.xammer.cloud.autospotting.pg;

import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs;
import com.xammer.cloud.service.autospotting.ledger.calc.CanonicalJson;
import com.xammer.cloud.service.autospotting.ledger.writer.LedgerInputsSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** Shared plumbing of the phase 17 containers tests: a DataSource on the harness database, leases, golden inputs. */
final class Ledger17Support {
    static final Path GOLDEN = Path.of("src/test/resources/ledger/golden");
    static final String LEASE = "ledger-writer:aws";

    record GoldenInputs(List<CalcInputs> days) {}

    private Ledger17Support() {
    }

    static DataSource dataSource(PgHarness.Database db) {
        return new DriverManagerDataSource(db.jdbcUrl(), db.username(), db.password());
    }

    static DataSourceTransactionManager txManager(DataSource ds) {
        return new DataSourceTransactionManager(ds);
    }

    /** Makes {@code holder} the holder of the job lease at {@code generation}, valid for an hour. */
    static void setLease(org.springframework.jdbc.core.JdbcTemplate jdbc, String holder, long generation) {
        jdbc.update("INSERT INTO autospotting_job_lease (name, holder, generation, acquired_at, expires_at)"
                + " VALUES (?, ?, ?, now(), now() + interval '1 hour') ON CONFLICT (name) DO UPDATE SET holder = ?,"
                + " generation = ?, expires_at = now() + interval '1 hour'", LEASE, holder, generation, holder, generation);
    }

    static LeaseHandle handle(String holder, long generation) {
        return new LeaseHandle(LeaseHandle.Kind.JOB, LEASE, holder, generation, Duration.ofMinutes(10),
                Duration.ofMinutes(1), Duration.ofSeconds(10), h -> true);
    }

    static GoldenInputs golden(String name) {
        try {
            return CanonicalJson.read(Files.readString(GOLDEN.resolve(name).resolve("inputs.json")), GoldenInputs.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<String> goldenNames() {
        try (Stream<Path> dirs = Files.list(GOLDEN)) {
            return dirs.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A {@link LedgerInputsSource} that serves fixed day inputs (the golden fixtures). */
    static LedgerInputsSource source(GoldenInputs inputs) {
        return new LedgerInputsSource() {
            @Override
            public List<Long> candidateAccounts() {
                return inputs.days().stream().map(d -> d.group().cloudAccountId()).distinct().toList();
            }

            @Override
            public Optional<LocalDate> firstRelevantDay(long accountId) {
                return inputs.days().stream().map(d -> d.windowStart().atZone(ZoneOffset.UTC).toLocalDate()).min(LocalDate::compareTo);
            }

            @Override
            public List<CalcInputs> inputsFor(long accountId, LocalDate day) {
                return inputs.days().stream().filter(d -> d.group().cloudAccountId() == accountId
                        && d.windowStart().atZone(ZoneOffset.UTC).toLocalDate().equals(day)).toList();
            }
        };
    }
}
