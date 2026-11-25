package com.xammer.cloud.xampulse.service.archive;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.regex.Pattern;

/**
 * Object keys in the customer's bucket. Everything lives under {@code xamops-xampulse/v1/}, the prefix the bucket
 * policy protects (TLS and SSE-KMS required, reads denied to everyone but the archive role and the account root) and
 * the only prefix the archive role may write. Keys are Hive-style partitioned and carry no instance or service name,
 * so listing the bucket reveals nothing about the customer's fleet.
 */
public final class ArchiveKeyLayout {

    public static final String ROOT = "xamops-xampulse/v1/";
    private static final Pattern ACCOUNT = Pattern.compile("^[0-9]{12}$");
    private static final Pattern REGION = Pattern.compile("^[a-z]{2}(-[a-z]+)+-[0-9]$");
    private static final Pattern NODE = Pattern.compile("^[A-Za-z0-9_-]{1,32}$");

    private ArchiveKeyLayout() {}

    /** Deterministic: the same hour, region, node and sequence always produce the same key, so a retry overwrites. */
    public static String logKey(String awsAccountId, String region, Instant hourStart, String node, int seq) {
        check(awsAccountId, region);
        if (node == null || !NODE.matcher(node).matches() || seq < 0) {
            throw new IllegalArgumentException("invalid node or sequence");
        }
        var t = hourStart.atZone(ZoneOffset.UTC);
        return String.format("%slogs/account=%s/region=%s/year=%04d/month=%02d/day=%02d/hour=%02d/part-%s-%d.parquet",
                ROOT, awsAccountId, region, t.getYear(), t.getMonthValue(), t.getDayOfMonth(), t.getHour(), node, seq);
    }

    /** One manifest per account and UTC day. */
    public static String manifestKey(String awsAccountId, LocalDate day) {
        check(awsAccountId, "us-east-1");
        return ROOT + "_manifest/logs/account=" + awsAccountId + "/dt=" + day + ".json";
    }

    public static String manifestPrefix(String awsAccountId) {
        check(awsAccountId, "us-east-1");
        return ROOT + "_manifest/logs/account=" + awsAccountId + "/";
    }

    private static void check(String account, String region) {
        if (account == null || !ACCOUNT.matcher(account).matches()) {
            throw new IllegalArgumentException("invalid AWS account id");
        }
        if (region == null || !REGION.matcher(region).matches()) {
            throw new IllegalArgumentException("invalid region");
        }
    }
}
