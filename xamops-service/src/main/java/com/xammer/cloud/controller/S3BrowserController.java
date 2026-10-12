package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.AwsClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/s3-browser")
public class S3BrowserController {

    private static final Logger log = LoggerFactory.getLogger(S3BrowserController.class);

    private static final Set<String> VIDEO_EXTENSIONS = new HashSet<>(Arrays.asList(
            ".mp4", ".mov", ".avi", ".mkv", ".webm", ".m4v", ".flv", ".wmv",
            ".mpeg", ".mpg", ".ts", ".mts", ".m2ts", ".3gp", ".ogv"
    ));

    /**
     * Hard ceiling on objects examined by a single recursive listing. A media bucket
     * that has been used as a MediaOps output target holds one .ts segment per ~10s of
     * every transcode — tens of thousands of keys — and walking all of them blows past
     * the 60s proxy timeout and 504s. Folder browsing (see {@link #browse}) is the
     * normal path and never hits this; recursive listing is only used for search.
     */
    private static final int MAX_OBJECTS_SCANNED = 20_000;

    private final CloudAccountRepository accountRepo;
    private final AwsClientProvider awsClientProvider;
    private final StsClient stsClient;

    /** account:bucket -> region. Resolving costs a HeadBucket round trip; the answer never changes. */
    private final Map<String, String> bucketRegionCache = new ConcurrentHashMap<>();

    public S3BrowserController(CloudAccountRepository accountRepo,
                                AwsClientProvider awsClientProvider,
                                StsClient stsClient) {
        this.accountRepo = accountRepo;
        this.awsClientProvider = awsClientProvider;
        this.stsClient = stsClient;
    }

    // ─── List all S3 buckets for a connected AWS account ──────────────────────

    @GetMapping("/buckets")
    public ResponseEntity<?> listBuckets(
            @RequestParam String accountId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        CloudAccount account = resolveAccount(accountId, userDetails);
        if (account == null) return ResponseEntity.status(404).body(Map.of("error", "Account not found"));
        if (!"AWS".equals(account.getProvider())) return ResponseEntity.badRequest().body(Map.of("error", "Only AWS accounts are supported"));

        try {
            S3Client s3 = awsClientProvider.getS3Client(account, "us-east-1");
            List<Map<String, Object>> buckets = s3.listBuckets().buckets().stream().map(b -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", b.name());
                m.put("createdAt", b.creationDate() != null ? b.creationDate().toString() : null);
                return m;
            }).collect(Collectors.toList());
            return ResponseEntity.ok(buckets);
        } catch (Exception e) {
            log.error("[S3Browser] listBuckets failed for account {}: {}", accountId, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ─── Browse one level of a bucket (folders + videos) ──────────────────────

    /**
     * Lists a single level of the bucket, the way the S3 console does: the folders
     * directly under {@code prefix}, plus the video files at that level.
     *
     * This is the picker's default view. Listing with a "/" delimiter means S3 collapses
     * everything below each folder into a CommonPrefix, so the response stays small no
     * matter how many objects the bucket holds — and customers who organise sources into
     * per-project folders see their own structure instead of one flat list where three
     * different "1.mp4"s are indistinguishable.
     */
    @GetMapping("/browse")
    public ResponseEntity<?> browse(
            @RequestParam String accountId,
            @RequestParam String bucket,
            @RequestParam(required = false, defaultValue = "") String prefix,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        CloudAccount account = resolveAccount(accountId, userDetails);
        if (account == null) return ResponseEntity.status(404).body(Map.of("error", "Account not found"));

        // This endpoint browses folders, so a prefix always names one. Without the trailing
        // slash S3 would treat "DRSAKSHI" as a partial match and the folder would come back
        // as its own child with an empty display name.
        if (!prefix.isBlank() && !prefix.endsWith("/")) prefix = prefix + "/";

        try {
            String region = getBucketRegion(account, bucket);
            S3Client s3 = awsClientProvider.getS3Client(account, region);

            List<Map<String, Object>> folders = new ArrayList<>();
            List<Map<String, Object>> files = new ArrayList<>();

            String token = null;
            do {
                ListObjectsV2Request.Builder req = ListObjectsV2Request.builder()
                        .bucket(bucket).delimiter("/").maxKeys(1000);
                if (!prefix.isBlank()) req.prefix(prefix);
                if (token != null) req.continuationToken(token);

                ListObjectsV2Response resp = s3.listObjectsV2(req.build());

                for (CommonPrefix cp : resp.commonPrefixes()) {
                    Map<String, Object> folder = new LinkedHashMap<>();
                    folder.put("name", folderName(cp.prefix(), prefix));
                    folder.put("prefix", cp.prefix());
                    folders.add(folder);
                }

                for (S3Object obj : resp.contents()) {
                    String key = obj.key();
                    // The zero-byte marker S3 creates for a "folder" is returned as an
                    // object at this level; it is not a file the user can pick.
                    if (key.endsWith("/")) continue;
                    if (isSidecar(key)) continue;
                    String ext = extension(key);
                    if (!VIDEO_EXTENSIONS.contains(ext)) continue;
                    files.add(fileItem(obj, ext, bucket, region));
                }

                token = resp.isTruncated() ? resp.nextContinuationToken() : null;
            } while (token != null);

            folders.sort(Comparator.comparing(m -> String.valueOf(m.get("name")).toLowerCase()));
            files.sort(Comparator.comparing(m -> String.valueOf(m.getOrDefault("lastModified", "")),
                    Comparator.reverseOrder()));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("bucket", bucket);
            body.put("prefix", prefix);
            body.put("region", region);
            body.put("folders", folders);
            body.put("files", files);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("[S3Browser] browse failed for {}/{}: {}", bucket, prefix, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ─── List video files in a bucket ─────────────────────────────────────────

    @GetMapping("/files")
    public ResponseEntity<?> listFiles(
            @RequestParam String accountId,
            @RequestParam String bucket,
            @RequestParam(required = false, defaultValue = "") String prefix,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        CloudAccount account = resolveAccount(accountId, userDetails);
        if (account == null) return ResponseEntity.status(404).body(Map.of("error", "Account not found"));

        try {
            String region = getBucketRegion(account, bucket);
            S3Client s3 = awsClientProvider.getS3Client(account, region);

            List<Map<String, Object>> items = new ArrayList<>();
            int scanned = 0;
            String token = null;
            do {
                ListObjectsV2Request.Builder req = ListObjectsV2Request.builder()
                        .bucket(bucket).maxKeys(1000);
                if (!prefix.isBlank()) req.prefix(prefix);
                if (token != null) req.continuationToken(token);

                ListObjectsV2Response resp = s3.listObjectsV2(req.build());
                for (S3Object obj : resp.contents()) {
                    String key = obj.key();
                    if (key.endsWith("/")) continue;
                    if (isSidecar(key)) continue;
                    String ext = extension(key);
                    if (!VIDEO_EXTENSIONS.contains(ext)) continue;
                    items.add(fileItem(obj, ext, bucket, region));
                }
                scanned += resp.keyCount() != null ? resp.keyCount() : 0;
                if (scanned >= MAX_OBJECTS_SCANNED) {
                    log.warn("[S3Browser] Stopped listing {}/{} after {} objects — returning {} videos found so far",
                            bucket, prefix, scanned, items.size());
                    break;
                }
                token = resp.isTruncated() ? resp.nextContinuationToken() : null;
            } while (token != null);

            items.sort(Comparator.comparing(m -> String.valueOf(m.getOrDefault("lastModified", "")),
                    Comparator.reverseOrder()));
            return ResponseEntity.ok(items);
        } catch (Exception e) {
            log.error("[S3Browser] listFiles failed for bucket {}: {}", bucket, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ─── Generate presigned URL for preview / transcode source ────────────────

    @PostMapping("/presign")
    public ResponseEntity<?> presign(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        String accountId = String.valueOf(body.get("accountId"));
        String bucket    = String.valueOf(body.get("bucket"));
        String key       = String.valueOf(body.get("key"));
        String region    = body.containsKey("region") ? String.valueOf(body.get("region")) : null;
        int expiry = body.containsKey("expiryMinutes")
                ? Integer.parseInt(String.valueOf(body.get("expiryMinutes"))) : 60;

        CloudAccount account = resolveAccount(accountId, userDetails);
        if (account == null) return ResponseEntity.status(404).body(Map.of("error", "Account not found"));

        try {
            if (region == null || region.isBlank() || "null".equals(region)) {
                region = getBucketRegion(account, bucket);
            }

            AwsCredentialsProvider creds = buildCredentialsProvider(account);

            // A presigned URL cannot outlive the credentials that signed it: when those are
            // STS session credentials, the link starts returning 403 the moment the session
            // ends, regardless of X-Amz-Expires. Signing for longer than the session lasts
            // therefore produces a URL that advertises validity it does not have — which is
            // how MediaOps ended up with stored links stamped X-Amz-Expires=43200 that were
            // dead within the hour. Cap the request to what the session can actually back.
            int effectiveExpiry = capToCredentialLifetime(creds, expiry);

            S3Presigner presigner = S3Presigner.builder()
                    .region(Region.of(region))
                    .credentialsProvider(creds)
                    .build();

            String url;
            try (presigner) {
                url = presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofMinutes(effectiveExpiry))
                        .getObjectRequest(r -> r.bucket(bucket).key(key))
                        .build()).url().toString();
            }

            return ResponseEntity.ok(Map.of("url", url, "expiresInMinutes", effectiveExpiry));
        } catch (Exception e) {
            log.error("[S3Browser] presign failed for {}/{}: {}", bucket, key, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private CloudAccount resolveAccount(String accountId, ClientUserDetails userDetails) {
        // accountId from the frontend is the provider account ID (AWS account number,
        // GCP project ID, or Azure subscription ID) — not the internal DB id.
        return accountRepo.findByProviderAccountId(accountId)
                .filter(a -> a.getClient() != null
                        && a.getClient().getId().equals(userDetails.getClientId()))
                .orElse(null);
    }

    /**
     * Resolve (and cache) the region a bucket lives in.
     *
     * GetBucketLocation signed for the wrong region fails under SigV4, so asking it from
     * a us-east-1 client and guessing on failure sent every request for a bucket outside
     * that region to the wrong endpoint, where the SDK spent its whole retry budget before
     * giving up — past the 60s proxy timeout, surfacing as an intermittent 504 and an empty
     * bucket list. Read the region from the {@code x-amz-bucket-region} header S3 returns
     * on HeadBucket instead, which is present both on success and on the wrong-region
     * redirect, and only fall back to GetBucketLocation if that yields nothing.
     *
     * Mirrors AccountS3Service.regionFor in media-ops-service.
     */
    private String getBucketRegion(CloudAccount account, String bucket) {
        String cacheKey = account.getAwsAccountId() + ":" + bucket;
        return bucketRegionCache.computeIfAbsent(cacheKey, k -> {
            S3Client probe = awsClientProvider.getS3Client(account, "us-east-1");

            try {
                HeadBucketResponse resp = probe.headBucket(b -> b.bucket(bucket));
                String r = resp.sdkHttpResponse().firstMatchingHeader("x-amz-bucket-region").orElse(null);
                if (r != null && !r.isBlank()) return r;
            } catch (S3Exception e) {
                String r = e.awsErrorDetails().sdkHttpResponse()
                        .firstMatchingHeader("x-amz-bucket-region").orElse(null);
                if (r != null && !r.isBlank()) return r;
                // Some wrong-region responses omit the header but name the region in the
                // message, e.g. "... expecting 'ap-south-1'".
                r = parseExpectedRegion(e.getMessage());
                if (r != null) return r;
            } catch (Exception ignored) { /* fall through */ }

            try {
                String loc = probe.getBucketLocation(b -> b.bucket(bucket)).locationConstraintAsString();
                if (loc != null && !loc.isBlank()) return loc;
                return "us-east-1"; // empty constraint means us-east-1
            } catch (Exception e) {
                log.warn("[S3Browser] Could not resolve region for bucket {} (defaulting ap-south-1): {}",
                        bucket, e.getMessage());
                return "ap-south-1";
            }
        });
    }

    private static final Pattern EXPECTING_REGION = Pattern.compile("expecting '([a-z0-9-]+)'");

    /** Extract the region S3 names in a wrong-region error message, if present. */
    private static String parseExpectedRegion(String message) {
        if (message == null) return null;
        Matcher m = EXPECTING_REGION.matcher(message);
        return m.find() ? m.group(1) : null;
    }

    /** Builds the JSON shape the picker renders for one selectable video. */
    private static Map<String, Object> fileItem(S3Object obj, String ext, String bucket, String region) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", obj.key());
        item.put("name", fileName(obj.key()));
        item.put("sizeBytes", obj.size());
        item.put("formattedSize", formatBytes(obj.size()));
        item.put("lastModified", obj.lastModified() != null ? obj.lastModified().toString() : null);
        item.put("extension", ext);
        item.put("bucket", bucket);
        item.put("region", region);
        return item;
    }

    /** "projects/promo/" under parent "projects/" displays as "promo". */
    private static String folderName(String commonPrefix, String parentPrefix) {
        String name = commonPrefix.substring(parentPrefix.length());
        return name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
    }

    /**
     * Shrink a requested presign window to the signing credentials' remaining lifetime,
     * less a small margin so a link does not expire on its last byte.
     *
     * <p>Long-lived credentials report no expiry, in which case the request stands.
     */
    private static int capToCredentialLifetime(AwsCredentialsProvider provider, int requestedMinutes) {
        try {
            var credentials = provider.resolveCredentials();
            if (!(credentials instanceof software.amazon.awssdk.auth.credentials.AwsSessionCredentials session)) {
                return requestedMinutes;
            }
            java.time.Instant expiry = session.expirationTime().orElse(null);
            if (expiry == null) return requestedMinutes;

            long usable = Duration.between(java.time.Instant.now(), expiry).toMinutes() - 5;
            if (usable < 1) usable = 1;
            if (usable >= requestedMinutes) return requestedMinutes;
            log.debug("[S3Browser] Presign window trimmed from {} to {} min by the signing session",
                    requestedMinutes, usable);
            return (int) usable;
        } catch (Exception e) {
            // Never block a presign over this; the uncapped window is the old behaviour.
            log.debug("[S3Browser] Could not read credential expiry: {}", e.getMessage());
            return requestedMinutes;
        }
    }

    private AwsCredentialsProvider buildCredentialsProvider(CloudAccount account) {
        if (account.getRoleArn() != null && !account.getRoleArn().isBlank()) {
            return StsAssumeRoleCredentialsProvider.builder()
                    .stsClient(stsClient)
                    .refreshRequest(AssumeRoleRequest.builder()
                            .roleArn(account.getRoleArn())
                            .roleSessionName("xamops-s3-browser")
                            .externalId(account.getExternalId())
                            .durationSeconds(3600)
                            .build())
                    .build();
        }
        return software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider.create();
    }

    private static String extension(String key) {
        int dot = key.lastIndexOf('.');
        return dot >= 0 ? key.substring(dot).toLowerCase() : "";
    }

    private static String fileName(String key) {
        int slash = key.lastIndexOf('/');
        return slash >= 0 ? key.substring(slash + 1) : key;
    }

    /**
     * True for the bookkeeping files macOS writes alongside real ones when copying to a
     * non-Apple filesystem — {@code ._Movie.mp4}, {@code __MACOSX/…}, {@code .DS_Store}.
     *
     * <p>An AppleDouble sidecar carries the original's name and extension but holds
     * resource-fork metadata, not video, so extension matching alone lists it as a
     * selectable source. Picking one produces a job that cannot succeed: FFmpeg reads a
     * few hundred bytes of nothing and exits. Keeping them out of the listing is the only
     * point where the distinction is still obvious.
     */
    private static boolean isSidecar(String key) {
        String name = fileName(key);
        return name.startsWith("._") || name.equals(".DS_Store") || key.startsWith("__MACOSX/")
                || key.contains("/__MACOSX/");
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
