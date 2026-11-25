package com.xammer.cloud.xampulse.service.setup;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.ResourceDto;
import com.xammer.cloud.service.AwsClientProvider;
import com.xammer.cloud.service.inventory.InventoryProjectionService;
import com.xammer.cloud.xampulse.dto.XpBucketCandidate;
import com.xammer.cloud.xampulse.dto.XpBucketIssue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.GetBucketEncryptionResponse;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryptionRule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Reads (never writes) the properties of the customer's buckets under the XamOps connection role and turns them
 * into the picker's badges and issues. In EXISTING mode the customer owns these settings, so findings are warnings.
 */
@Service("xpBucketHealthService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class BucketHealthService {

    private static final Logger log = LoggerFactory.getLogger(BucketHealthService.class);
    static final int MAX_BUCKETS = 200;

    /** Overall budget for probing all buckets. Kept under the frontend's query timeout (~20s) with headroom. */
    private static final java.time.Duration PROBE_BUDGET = java.time.Duration.ofSeconds(15);

    /** What was read from S3. Null means "could not be read". */
    public record Facts(String name, String region, String encryption, Boolean versioning, Boolean publicAccessBlocked,
                        Boolean lifecycleHasXamPulseRule) {}

    private final AwsClientProvider aws;
    private final InventoryProjectionService inventory;

    public BucketHealthService(AwsClientProvider aws, InventoryProjectionService inventory) {
        this.aws = aws;
        this.inventory = inventory;
    }

    public List<XpBucketCandidate> buckets(CloudAccount account) {
        List<String[]> named = names(account);
        List<String[]> capped = named.subList(0, Math.min(MAX_BUCKETS, named.size()));
        List<XpBucketCandidate> out = probeAll(account, capped);
        out.sort(Comparator.comparing(XpBucketCandidate::recommended).reversed()
                .thenComparing(XpBucketCandidate::name));
        return out;
    }

    /**
     * Probes every bucket in parallel under an overall time budget. Each bucket needs several sequential S3
     * reads (location, encryption, versioning, public-access, lifecycle); done serially over a real account's
     * buckets this easily exceeds the client's ~20s timeout, which showed up as "data store not reachable" in
     * the picker. A bounded pool runs the probes concurrently; any bucket that does not finish in time is still
     * returned with UNKNOWN facts rather than failing the whole list.
     */
    private List<XpBucketCandidate> probeAll(CloudAccount account, List<String[]> named) {
        if (named.isEmpty()) {
            return new ArrayList<>();
        }
        int poolSize = Math.min(16, Math.max(1, named.size()));
        ExecutorService pool = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "xp-bucket-probe");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<XpBucketCandidate>> futures = new ArrayList<>();
            for (String[] nr : named) {
                futures.add(pool.submit(() -> toCandidate(probe(account, nr[0], nr[1]))));
            }
            long deadline = System.nanoTime() + PROBE_BUDGET.toNanos();
            List<XpBucketCandidate> out = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                long remaining = deadline - System.nanoTime();
                String[] nr = named.get(i);
                try {
                    out.add(futures.get(i).get(Math.max(0, remaining), TimeUnit.NANOSECONDS));
                } catch (TimeoutException e) {
                    futures.get(i).cancel(true);
                    out.add(toCandidate(new Facts(nr[0], nr[1], "UNKNOWN", null, null, null)));
                    log.warn("XamPulse bucket probe timed out for {} (account {})", nr[0], account.getId());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    out.add(toCandidate(new Facts(nr[0], nr[1], "UNKNOWN", null, null, null)));
                } catch (ExecutionException e) {
                    out.add(toCandidate(new Facts(nr[0], nr[1], "UNKNOWN", null, null, null)));
                    log.warn("XamPulse bucket probe failed for {} (account {}): {}", nr[0], account.getId(), e.getCause() == null ? e.toString() : e.getCause().toString());
                }
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    /** {name, region}. Inventory when fresh, otherwise a live ListBuckets. */
    private List<String[]> names(CloudAccount account) {
        List<String[]> out = new ArrayList<>();
        if (inventory.isFresh(account.getId())) {
            for (ResourceDto r : inventory.byType(account.getId(), "S3 Bucket")) {
                out.add(new String[] {r.getId(), r.getRegion()});
            }
            return out;
        }
        try {
            S3Client s3 = aws.getS3Client(account, "us-east-1");
            for (Bucket b : s3.listBuckets().buckets()) {
                out.add(new String[] {b.name(), regionOf(s3, b.name())});
            }
        } catch (RuntimeException e) {
            log.warn("XamPulse bucket listing failed for account {}: {}", account.getId(), e.toString());
        }
        return out;
    }

    private static String regionOf(S3Client s3, String bucket) {
        try {
            String loc = s3.getBucketLocation(r -> r.bucket(bucket)).locationConstraintAsString();
            return loc == null || loc.isBlank() ? "us-east-1" : ("EU".equals(loc) ? "eu-west-1" : loc);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Reads one bucket. Each property is read on its own so one denied call does not hide the rest. */
    public Facts probe(CloudAccount account, String bucket, String regionHint) {
        String region = regionHint;
        S3Client base = aws.getS3Client(account, "us-east-1");
        if (region == null || region.isBlank()) {
            region = regionOf(base, bucket);
        }
        if (region == null) {
            return new Facts(bucket, null, "UNKNOWN", null, null, null);
        }
        S3Client s3 = aws.getS3Client(account, region);
        String enc = "UNKNOWN";
        try {
            GetBucketEncryptionResponse r = s3.getBucketEncryption(b -> b.bucket(bucket));
            enc = encryption(r);
        } catch (S3Exception e) {
            if ("ServerSideEncryptionConfigurationNotFoundError".equals(e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode())) {
                enc = "NONE";
            }
        } catch (RuntimeException ignored) {
            // leave UNKNOWN
        }
        Boolean versioning = null;
        try {
            versioning = s3.getBucketVersioning(b -> b.bucket(bucket)).status() == BucketVersioningStatus.ENABLED;
        } catch (RuntimeException ignored) {
            // leave null
        }
        Boolean pab = null;
        try {
            var c = s3.getPublicAccessBlock(b -> b.bucket(bucket)).publicAccessBlockConfiguration();
            pab = Boolean.TRUE.equals(c.blockPublicAcls()) && Boolean.TRUE.equals(c.blockPublicPolicy())
                    && Boolean.TRUE.equals(c.ignorePublicAcls()) && Boolean.TRUE.equals(c.restrictPublicBuckets());
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                pab = false;
            }
        } catch (RuntimeException ignored) {
            // leave null
        }
        Boolean lifecycle = null;
        try {
            lifecycle = hasXamPulseRule(s3.getBucketLifecycleConfiguration(b -> b.bucket(bucket)).rules());
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                lifecycle = false;
            }
        } catch (RuntimeException ignored) {
            // leave null
        }
        return new Facts(bucket, region, enc, versioning, pab, lifecycle);
    }

    static String encryption(GetBucketEncryptionResponse r) {
        if (r == null || r.serverSideEncryptionConfiguration() == null) {
            return "NONE";
        }
        for (ServerSideEncryptionRule rule : r.serverSideEncryptionConfiguration().rules()) {
            if (rule.applyServerSideEncryptionByDefault() == null) {
                continue;
            }
            String alg = rule.applyServerSideEncryptionByDefault().sseAlgorithmAsString();
            if ("aws:kms".equals(alg) || "aws:kms:dsse".equals(alg)) {
                return "SSE_KMS";
            }
            if ("AES256".equals(alg)) {
                return "SSE_S3";
            }
        }
        return "UNKNOWN";
    }

    static boolean hasXamPulseRule(List<LifecycleRule> rules) {
        for (LifecycleRule r : rules) {
            String prefix = r.filter() == null ? r.prefix() : r.filter().prefix();
            if (prefix == null && r.filter() != null && r.filter().and() != null) {
                prefix = r.filter().and().prefix();
            }
            if (prefix != null && prefix.startsWith(RetentionPolicyService.PREFIX)) {
                return true;
            }
        }
        return false;
    }

    /** Pure conversion from facts to the picker row. */
    public static XpBucketCandidate toCandidate(Facts f) {
        List<XpBucketIssue> issues = new ArrayList<>();
        if (f.name().startsWith(SetupPlanValidator.CUR_PREFIX)) {
            issues.add(new XpBucketIssue("COST_REPORT_BUCKET", "WARN",
                    "This looks like a cost report bucket. The XamOps connection role can write to buckets with this prefix. Use a different bucket."));
        }
        if ("NONE".equals(f.encryption())) {
            issues.add(new XpBucketIssue("BUCKET_NOT_ENCRYPTED", "WARN", "The bucket has no default encryption. Archive objects are still written with SSE-KMS."));
        } else if ("SSE_S3".equals(f.encryption())) {
            issues.add(new XpBucketIssue("BUCKET_SSE_S3", "WARN",
                    "Default encryption is SSE-S3. Archive objects are written with SSE-KMS; a customer key gives you key-level control."));
        } else if ("UNKNOWN".equals(f.encryption())) {
            issues.add(new XpBucketIssue("ENCRYPTION_UNREADABLE", "WARN", "Encryption settings could not be read."));
        }
        if (Boolean.FALSE.equals(f.publicAccessBlocked())) {
            issues.add(new XpBucketIssue("BUCKET_PUBLIC_NOT_BLOCKED", "WARN", "Block Public Access is not fully enabled on this bucket."));
        }
        if (Boolean.FALSE.equals(f.versioning())) {
            issues.add(new XpBucketIssue("VERSIONING_OFF", "WARN", "Versioning is off. Enable it so an overwritten archive file can be recovered."));
        }
        if (Boolean.FALSE.equals(f.lifecycleHasXamPulseRule())) {
            issues.add(new XpBucketIssue("NO_LIFECYCLE_RULE", "WARN",
                    "No lifecycle rule covers xamops-xampulse/. Apply the lifecycle JSON from the preview to control cost and retention."));
        }
        boolean recommended = f.name().startsWith("xamops-xampulse-")
                || ("SSE_KMS".equals(f.encryption()) && Boolean.TRUE.equals(f.versioning()) && Boolean.TRUE.equals(f.publicAccessBlocked())
                && !f.name().startsWith(SetupPlanValidator.CUR_PREFIX));
        return new XpBucketCandidate(f.name(), f.region(), f.encryption(), f.versioning(), f.publicAccessBlocked(),
                f.lifecycleHasXamPulseRule(), recommended, List.copyOf(issues));
    }
}
