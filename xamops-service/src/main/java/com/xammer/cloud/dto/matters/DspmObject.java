package com.xammer.cloud.dto.matters;

import java.util.List;

/**
 * One object discovered inside an asset — a file, table or column.
 *
 * @param objectType {@code FILE}, {@code TABLE} or {@code COLUMN}. A single on-prem datastore returns a
 *                   mix (columns from the schema, plus files from an export), so this cannot be assumed
 *                   from the parent asset's type.
 * @param source     how it was discovered: {@code CLOUD_SCAN}, {@code SAAS}, {@code ENDPOINT_AGENT} or
 *                   {@code DATASTORE_COLUMN}.
 * @param path       full path. For cloud objects this is an {@code s3://} style URI; the vendor console
 *                   offers it for copy-paste rather than as a link, because reaching it needs the
 *                   customer's own cloud credentials.
 * @param findings   sensitive matches broken down by detected entity type. Empty when nothing matched.
 */
public record DspmObject(
        String id,
        String name,
        String objectType,
        String source,
        String path,
        String extension,
        Long sizeBytes,
        boolean sensitive,
        long sensitiveCount,
        List<Finding> findings
) {

    /**
     * @param entityType    the detector that fired, e.g. {@code SHA KEY}.
     * @param category      raw category code, e.g. {@code SECRETS}.
     * @param categoryLabel console wording, e.g. {@code IT & Security}.
     * @param count         instances in this object.
     */
    public record Finding(String entityType, String category, String categoryLabel, long count) {
    }
}
