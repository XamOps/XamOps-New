package com.xammer.cloud.xampulse.service.archive;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Reads one closed hour of one tenant's logs in one region as a Parquet file (zstd) into a local temp file. Parquet
 * cannot be produced over JDBC, so the production implementation uses the ClickHouse HTTP interface.
 */
public interface LogHourExporter {

    record Exported(long rows, long minTsMs, long maxTsMs) {}

    /** Writes the file and returns its stats, or {@code null} when the hour has no rows (nothing is written). */
    Exported export(long tenantId, String region, Instant hourStart, Path target) throws IOException;
}
