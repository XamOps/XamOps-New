package com.xammer.cloud.xampulse.service.archive;

import java.util.List;

/** The per-day manifest: what was archived, so an estimate or a restore never has to list the whole bucket. */
public record ArchiveManifest(int schemaVersion, String account, String date, List<Entry> files) {

    public static final int SCHEMA_VERSION = 1;

    public record Entry(String key, String region, long rows, long bytes, long minTsMs, long maxTsMs, String sha256, String storageClass) {}
}
