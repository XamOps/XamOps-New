package com.xammer.cloud.xampulse.service.archive;

import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.dto.XpLogRow;
import com.xammer.cloud.xampulse.service.query.FilterAst;
import com.xammer.cloud.xampulse.service.query.QueryScope;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Seam between the job runner and the thing that actually reads archived Parquet files. The file list is passed in
 * (it comes from the tenant's own manifests); an implementation never lists a bucket or accepts a key from a caller.
 */
public interface ArchiveSearchExecutor {

    record Result(List<XpLogRow> rows, boolean truncated) {}

    @FunctionalInterface
    interface ProgressSink {
        void filesDone(int done);
    }

    /** Stable failure codes: the only error text a caller ever sees. */
    class Failure extends RuntimeException {
        public static final String ACCESS_DENIED = "ARCHIVE_ACCESS_DENIED";
        public static final String OBJECT_MISSING = "ARCHIVE_OBJECT_MISSING";
        public static final String TIMEOUT = "ARCHIVE_TIMEOUT";
        public static final String INTERNAL = "ARCHIVE_INTERNAL";

        private final String code;

        public Failure(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /**
     * @param scope single tenant plus the requested time range
     * @param files manifest entries to read, oldest first
     * @return at most {@code rowLimit} rows, newest first
     */
    Result search(XpAccountConfig cfg, String awsAccountId, QueryScope scope, List<ArchiveManifest.Entry> files,
                  FilterAst filter, int rowLimit, ProgressSink progress, BooleanSupplier cancelled);
}
