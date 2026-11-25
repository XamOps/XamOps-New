package com.xammer.cloud.xampulse.service.setup;

import com.xammer.cloud.xampulse.dto.XpRetention;
import com.xammer.cloud.xampulse.dto.XpSetupPlanRequest;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Server-side rules for a setup plan (contract 2.9). Pure and side-effect free: every boundary is unit tested. All
 * errors are collected and returned together so the form can show them at once.
 */
@Component("xpSetupPlanValidator")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class SetupPlanValidator {

    public static final Pattern REGION = Pattern.compile("^[a-z]{2}(-[a-z]+)+-\\d$");
    public static final Pattern BUCKET = Pattern.compile("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$");
    public static final Pattern INSTANCE_ID = Pattern.compile("^i-[0-9a-f]{8,17}$");
    public static final Pattern KMS_ARN = Pattern.compile("^arn:aws[a-z-]*:kms:[a-z0-9-]+:\\d{12}:key/[A-Za-z0-9-]+$");
    private static final Pattern IP_LIKE = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Pattern TAG_TEXT = Pattern.compile("^[A-Za-z0-9 _.:/=+@-]{1,128}$");
    private static final Pattern SUFFIX = Pattern.compile("^-[a-z0-9]{4}$");

    public static final int MAX_IDS_PER_REGION = 500;
    public static final int CHUNK_SIZE = 50;
    public static final String CUR_PREFIX = "xamops-cur-";

    /** Result of a valid plan. {@code chunks} holds, per region, the instance ids split into groups of at most 50. */
    public record Validated(String bucketName, List<String> warnings, Map<String, List<List<String>>> chunks) {}

    public Validated validate(XpSetupPlanRequest req, String awsAccountId) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, List<List<String>>> chunks = new LinkedHashMap<>();

        if (req == null || req.bucket() == null || req.kms() == null || req.retention() == null || req.regions() == null) {
            throw XamPulseException.invalid("The plan is incomplete.");
        }
        if (awsAccountId == null || !awsAccountId.matches("^\\d{12}$")) {
            throw XamPulseException.invalid("This account has no valid AWS account id.");
        }

        checkRegion("homeRegion", req.homeRegion(), errors);
        retention(req.retention(), errors);

        // ---- bucket ----
        String mode = req.bucket().mode();
        boolean create = "CREATE".equals(mode);
        boolean existing = "EXISTING".equals(mode);
        if (!create && !existing) {
            errors.add("bucket.mode must be CREATE or EXISTING.");
        }
        checkRegion("bucket.region", req.bucket().region(), errors);
        String name = req.bucket().name();
        if (create) {
            String base = defaultBucketName(awsAccountId, req.bucket().region());
            if (name == null || name.isBlank()) {
                name = base;
            } else if (!(name.equals(base) || (name.startsWith(base) && SUFFIX.matcher(name.substring(base.length())).matches()))) {
                errors.add("A new bucket must be named " + base + " (optionally followed by a 4 character suffix).");
            }
            if (req.bucket().region() != null && req.homeRegion() != null && !req.bucket().region().equals(req.homeRegion())) {
                errors.add("A new bucket is created in the home region, so bucket.region must equal homeRegion.");
            }
        } else if (existing) {
            if (name == null || name.isBlank()) {
                errors.add("Choose the existing bucket.");
            }
        }
        if (name != null && !name.isBlank()) {
            if (!BUCKET.matcher(name).matches() || name.contains("..") || name.contains(".-") || name.contains("-.")
                    || IP_LIKE.matcher(name).matches() || name.startsWith("xn--") || name.endsWith("-s3alias")) {
                errors.add("Bucket name " + name + " is not a valid S3 bucket name.");
            } else if (name.startsWith(CUR_PREFIX)) {
                if (create) {
                    errors.add("Bucket names starting with " + CUR_PREFIX + " are reserved for cost reports.");
                } else {
                    warnings.add("This bucket name starts with " + CUR_PREFIX + ". The XamOps connection role has write grants on buckets"
                            + " with that prefix for cost reports. Use a different bucket for logs.");
                }
            }
        }

        // ---- KMS ----
        String kmsMode = req.kms().mode();
        if (kmsMode == null || !Set.of("CREATE", "CUSTOMER_KEY", "AWS_MANAGED").contains(kmsMode)) {
            errors.add("kms.mode must be CREATE, CUSTOMER_KEY or AWS_MANAGED.");
        } else {
            if ("AWS_MANAGED".equals(kmsMode)) {
                if (!existing) {
                    errors.add("AWS managed encryption is only available with an existing bucket.");
                } else {
                    warnings.add("With AWS managed encryption the XamOps connection role can read the archive unless you apply the"
                            + " deny snippet shown in the preview.");
                }
            }
            if ("CREATE".equals(kmsMode) && !create) {
                errors.add("A new KMS key can only be created together with a new bucket.");
            }
            if ("CUSTOMER_KEY".equals(kmsMode)) {
                String arn = req.kms().keyArn();
                if (arn == null || !KMS_ARN.matcher(arn).matches()) {
                    errors.add("kms.keyArn must be a KMS key ARN when you bring your own key.");
                }
            }
        }

        // ---- regions ----
        if (req.regions().isEmpty()) {
            errors.add("Select at least one region.");
        }
        Set<String> seen = new HashSet<>();
        for (XpSetupPlanRequest.RegionSelection r : req.regions()) {
            if (r == null) {
                errors.add("A region entry is empty.");
                continue;
            }
            checkRegion("regions.region", r.region(), errors);
            if (r.region() != null && !seen.add(r.region())) {
                errors.add("Region " + r.region() + " is listed twice.");
            }
            List<List<String>> regionChunks = target(r.region(), r.targetMode(), r.instanceIds(), r.tagKey(), r.tagValue(),
                    r.confirmAllManaged(), errors);
            if (regionChunks != null && r.region() != null) {
                chunks.put(r.region(), regionChunks);
            }
        }

        if (req.sources() != null && req.sources().custom() != null) {
            for (XpSetupPlanRequest.CustomSource c : req.sources().custom()) {
                String p = c == null ? null : c.path();
                if (p == null || !p.startsWith("/") || p.contains("..") || p.contains("\n") || p.length() > 512) {
                    errors.add("Custom log paths must be absolute and must not contain '..'.");
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new XamPulseException("XP_INVALID_REQUEST", 400, errors.get(0), Map.of("errors", errors));
        }
        return new Validated(name, List.copyOf(warnings), chunks);
    }

    /** Checks one region's target and returns its id chunks (empty list for TAG / ALL_MANAGED). Used by region plans too. */
    public List<List<String>> target(String region, String targetMode, List<String> ids, String tagKey, String tagValue,
                                     Boolean confirmAllManaged, List<String> errors) {
        String where = region == null ? "region" : region;
        if (targetMode == null || !Set.of("INSTANCES", "ALL_MANAGED", "TAG").contains(targetMode)) {
            errors.add(where + ": targetMode must be INSTANCES, ALL_MANAGED or TAG.");
            return null;
        }
        switch (targetMode) {
            case "ALL_MANAGED" -> {
                if (!Boolean.TRUE.equals(confirmAllManaged)) {
                    errors.add(where + ": confirm that every SSM-managed instance in the region should be covered.");
                }
                return List.of();
            }
            case "TAG" -> {
                if (tagKey == null || !TAG_TEXT.matcher(tagKey).matches()) {
                    errors.add(where + ": tagKey is required for tag targeting.");
                }
                if (tagValue != null && !tagValue.isEmpty() && !TAG_TEXT.matcher(tagValue).matches()) {
                    errors.add(where + ": tagValue contains characters that are not allowed.");
                }
                return List.of();
            }
            default -> {
                if (ids == null || ids.isEmpty()) {
                    errors.add(where + ": select at least one instance, or choose tag or all-managed targeting.");
                    return null;
                }
                if (ids.size() > MAX_IDS_PER_REGION) {
                    errors.add(where + ": more than " + MAX_IDS_PER_REGION + " instances selected. Use tag targeting or confirm all-managed.");
                    return null;
                }
                List<String> unique = new ArrayList<>(new java.util.LinkedHashSet<>(ids));
                for (String id : unique) {
                    if (id == null || !INSTANCE_ID.matcher(id).matches()) {
                        errors.add(where + ": " + id + " is not an instance id.");
                        return null;
                    }
                }
                return chunk(unique);
            }
        }
    }

    public static List<List<String>> chunk(List<String> ids) {
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += CHUNK_SIZE) {
            out.add(List.copyOf(ids.subList(i, Math.min(ids.size(), i + CHUNK_SIZE))));
        }
        return out;
    }

    public static String defaultBucketName(String awsAccountId, String region) {
        return "xamops-xampulse-" + awsAccountId + "-" + region;
    }

    /** hotDays 3..30; IA >= 30; Glacier IR >= 90 and >= IA + 30; expire >= Glacier IR + 90 and <= 3650. */
    public void retention(XpRetention r, List<String> errors) {
        if (r.hotDays() < 3 || r.hotDays() > 30) {
            errors.add("Hot retention must be between 3 and 30 days.");
        }
        if (r.iaAfterDays() < 30) {
            errors.add("Standard-IA transition must be at least 30 days.");
        }
        if (r.glacierIrAfterDays() < 90 || r.glacierIrAfterDays() < r.iaAfterDays() + 30) {
            errors.add("Glacier Instant Retrieval transition must be at least 90 days and 30 days after Standard-IA.");
        }
        if (r.expireAfterDays() < r.glacierIrAfterDays() + 90) {
            errors.add("Expiry must be at least 90 days after the Glacier Instant Retrieval transition.");
        }
        if (r.expireAfterDays() > 3650) {
            errors.add("Expiry cannot be longer than 3650 days.");
        }
    }

    private void checkRegion(String field, String region, List<String> errors) {
        if (region == null || !REGION.matcher(region).matches()) {
            errors.add(field + " is not a valid AWS region.");
        }
    }
}
