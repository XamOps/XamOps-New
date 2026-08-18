package com.xammer.mediaops.service;

import com.xammer.cloud.permissions.AwsPermissionCatalog;
import com.xammer.cloud.permissions.AwsPermissionInterceptor;
import com.xammer.cloud.permissions.XamOpsExecutionAttributes;
import com.xammer.mediaops.domain.CloudAccount;
import com.xammer.mediaops.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes and serves transcoded output stored in a bucket of the customer's own
 * connected AWS account. Credentials come from assuming that account's onboarding
 * IAM role (the same cross-account role XamOps uses to read the account elsewhere)
 * — no access keys are stored. Playback and download stream back through the
 * media-ops proxy, so the customer bucket never needs to be public and every
 * output format (HLS/DASH/MP4/…) works uniformly.
 */
@Service
public class AccountS3Service {

    private static final Logger log = LoggerFactory.getLogger(AccountS3Service.class);

    /**
     * Root prefix under which all XamOps transcode output is written in a customer bucket.
     * Must stay in step with the XamOpsMediaOpsOutputWrite statement in the onboarding
     * CloudFormation templates, which scopes s3:PutObject to exactly this prefix.
     */
    public static final String OUTPUT_ROOT = MediaOutputPath.OUTPUT_ROOT;

    private final CloudAccountRepository accountRepo;
    private final StsClient stsClient;
    private final AwsPermissionInterceptor permissionInterceptor;

    // Cache assume-role S3 clients by account:region (credentials auto-refresh) and
    // resolved bucket regions by account:bucket — HLS playback fetches many segments.
    private final Map<String, S3Client> clientCache = new ConcurrentHashMap<>();
    private final Map<String, String> bucketRegionCache = new ConcurrentHashMap<>();

    public AccountS3Service(CloudAccountRepository accountRepo, StsClient stsClient,
            AwsPermissionInterceptor permissionInterceptor) {
        this.accountRepo = accountRepo;
        this.stsClient = stsClient;
        this.permissionInterceptor = permissionInterceptor;
    }

    // ─── Output storage ─────────────────────────────────────────────────────────

    /**
     * Confirm the account bucket is writable with the assumed role by writing and
     * deleting a marker object. Throws IllegalArgumentException with a clear message
     * when the role lacks write access (e.g. a read-only onboarded account) so job
     * submission fails fast instead of only breaking after transcoding.
     */
    public void verifyWritable(String awsAccountId, Long clientId, String bucket) {
        CloudAccount account = requireAccount(awsAccountId, clientId);
        String region = regionFor(account, bucket);
        S3Client s3 = clientFor(account, region);
        String testKey = OUTPUT_ROOT + "/.xamops-write-check-" + UUID.randomUUID();
        try {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(testKey).build(),
                    RequestBody.fromString("xamops"));
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(testKey).build());
        } catch (S3Exception e) {
            throw new IllegalArgumentException("Cannot write to bucket '" + bucket + "' in account "
                    + awsAccountId + ": " + e.awsErrorDetails().errorMessage() + remedyFor(e));
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot write to bucket '" + bucket + "': " + e.getMessage());
        }
    }

    /**
     * Advice appended to a failed write check, chosen by error code.
     *
     * A denial and a bad credential look alike to a user — both surface as a 403 that says
     * the write did not happen — but they need opposite responses, and telling someone to
     * go edit CloudFormation when their credentials are simply stale sends them rebuilding
     * infrastructure that was never broken.
     */
    private static String remedyFor(S3Exception e) {
        String code = e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : null;
        if (code == null) return "";
        switch (code) {
            case "AccessDenied":
            case "AllAccessDisabled":
                // Both onboarding stacks grant s3:PutObject/DeleteObject under OUTPUT_ROOT,
                // so a genuine denial means the account still runs an IAM role created
                // before that — re-deploying the stack is the fix, not switching to
                // read-write onboarding.
                return " — update the XamOps CloudFormation stack in this account so its role"
                        + " allows s3:PutObject under " + OUTPUT_ROOT + "/.";
            case "InvalidAccessKeyId":
            case "ExpiredToken":
            case "TokenRefreshRequired":
            case "SignatureDoesNotMatch":
                // Not a permissions problem: the cached assume-role session is no longer
                // valid, usually because the IAM role was recreated after this client was
                // built. Nothing in the customer's account needs changing.
                return " — this is a stale credential, not a missing permission;"
                        + " the cached session for this account needs to be rebuilt.";
            case "NoSuchBucket":
                return " — the bucket no longer exists in this account.";
            default:
                return "";
        }
    }

    /**
     * Upload every regular file under {@code localDir} to the account bucket beneath
     * {@code keyPrefix}, preserving relative layout. Returns the resolved bucket region
     * so the caller can persist it for playback/download.
     */
    public String uploadDirectory(String awsAccountId, Long clientId, String bucket,
                                  Path localDir, String keyPrefix) throws IOException {
        CloudAccount account = requireAccount(awsAccountId, clientId);
        String region = regionFor(account, bucket);
        S3Client s3 = clientFor(account, region);
        try (java.util.stream.Stream<Path> walk = Files.walk(localDir)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                String relative = localDir.relativize(file).toString().replace('\\', '/');
                String key = keyPrefix + "/" + relative;
                s3.putObject(PutObjectRequest.builder()
                                .bucket(bucket).key(key)
                                .contentType(contentTypeFor(relative))
                                .build(),
                        RequestBody.fromFile(file));
                log.debug("[AccountS3] Uploaded s3://{}/{}", bucket, key);
            });
        }
        return region;
    }

    /**
     * Stream one output object from the account bucket to an HTTP response with
     * Range support (see {@link UserS3Service#streamObject} for why this makes every
     * output format playable while the bucket stays private).
     */
    public void streamObject(String awsAccountId, Long clientId, String bucket, String region,
                             String key, String rangeHeader, HttpServletResponse response) throws IOException {
        CloudAccount account = requireAccount(awsAccountId, clientId);
        String resolvedRegion = (region != null && !region.isBlank()) ? region : regionFor(account, bucket);
        S3Client s3 = clientFor(account, resolvedRegion);
        try {
            GetObjectRequest.Builder reqBuilder = GetObjectRequest.builder().bucket(bucket).key(key);
            if (rangeHeader != null && !rangeHeader.isBlank()) {
                reqBuilder.range(rangeHeader);
            }
            ResponseInputStream<GetObjectResponse> in = s3.getObject(reqBuilder.build());
            GetObjectResponse meta = in.response();

            response.setContentType(contentTypeFor(key));
            response.setHeader("Accept-Ranges", "bytes");
            if (meta.contentLength() != null) {
                response.setHeader("Content-Length", String.valueOf(meta.contentLength()));
            }
            if (rangeHeader != null && !rangeHeader.isBlank() && meta.contentRange() != null) {
                response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
                response.setHeader("Content-Range", meta.contentRange());
            } else {
                response.setStatus(HttpServletResponse.SC_OK);
            }
            try (in) {
                in.transferTo(response.getOutputStream());
            }
        } catch (NoSuchKeyException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    /** Write every object under {@code keyPrefix} into a ZIP stream (for downloads). */
    public boolean writeObjectsUnderPrefix(String awsAccountId, Long clientId, String bucket, String region,
                                           String keyPrefix, ZipOutputStream zip) throws IOException {
        CloudAccount account = requireAccount(awsAccountId, clientId);
        String resolvedRegion = (region != null && !region.isBlank()) ? region : regionFor(account, bucket);
        S3Client s3 = clientFor(account, resolvedRegion);
        boolean wrote = false;
        String normalizedPrefix = keyPrefix.endsWith("/") ? keyPrefix : keyPrefix + "/";
        String continuationToken = null;
        do {
            ListObjectsV2Request.Builder rb = ListObjectsV2Request.builder()
                    .bucket(bucket).prefix(normalizedPrefix).maxKeys(1000);
            if (continuationToken != null) rb.continuationToken(continuationToken);
            ListObjectsV2Response resp = s3.listObjectsV2(rb.build());
            for (S3Object obj : resp.contents()) {
                if (obj.key().endsWith("/")) continue;
                String entryName = obj.key().substring(normalizedPrefix.length());
                if (entryName.isEmpty()) continue;
                zip.putNextEntry(new ZipEntry(entryName));
                try (ResponseInputStream<GetObjectResponse> in = s3.getObject(
                        GetObjectRequest.builder().bucket(bucket).key(obj.key()).build())) {
                    in.transferTo(zip);
                }
                zip.closeEntry();
                wrote = true;
            }
            continuationToken = Boolean.TRUE.equals(resp.isTruncated()) ? resp.nextContinuationToken() : null;
        } while (continuationToken != null);
        return wrote;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private CloudAccount requireAccount(String awsAccountId, Long clientId) {
        CloudAccount account = accountRepo.findByAwsAccountIdAndClientId(awsAccountId, clientId)
                .orElseThrow(() -> new NoSuchElementException("Connected AWS account not found: " + awsAccountId));
        if (account.getRoleArn() == null || account.getRoleArn().isBlank()) {
            throw new IllegalArgumentException("Account " + awsAccountId + " has no IAM role configured.");
        }
        return account;
    }

    /** Build (or reuse) an assume-role S3 client for an account in a specific region. */
    private S3Client clientFor(CloudAccount account, String region) {
        String cacheKey = account.getAwsAccountId() + ":" + region;
        return clientCache.computeIfAbsent(cacheKey, k -> S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StsAssumeRoleCredentialsProvider.builder()
                        .stsClient(stsClient)
                        .refreshRequest(req -> req
                                .roleArn(account.getRoleArn())
                                .externalId(account.getExternalId())
                                .roleSessionName("xamops-mediaops-" + account.getAwsAccountId()))
                        .build())
                // If the customer trims s3: actions off the onboarding role, MediaOps
                // storage silently empties. Recording the denial here is what lets the
                // UI say which action is missing instead of showing an empty bucket.
                .overrideConfiguration(permissionTracking(account, region))
                .build());
    }

    /** Stamps the account onto the client so denials land in the shared permission-gap store. */
    private ClientOverrideConfiguration permissionTracking(CloudAccount account, String region) {
        ClientOverrideConfiguration.Builder builder = ClientOverrideConfiguration.builder();
        if (account.getAwsAccountId() == null) {
            return builder.build();
        }
        return builder
                .putExecutionAttribute(XamOpsExecutionAttributes.ACCOUNT_ID, account.getAwsAccountId())
                .putExecutionAttribute(XamOpsExecutionAttributes.IAM_SERVICE_PREFIX,
                        AwsPermissionCatalog.iamPrefixForClient("S3Client", null))
                .putExecutionAttribute(XamOpsExecutionAttributes.REGION, region)
                .addExecutionInterceptor(permissionInterceptor)
                .build();
    }

    /**
     * Resolve (and cache) the region a bucket lives in. GetBucketLocation signed for
     * the wrong region fails under SigV4 ("authorization header malformed; region X is
     * wrong"), so we rely on the {@code x-amz-bucket-region} header S3 returns from a
     * HeadBucket — present both on success and on the wrong-region redirect. Only if
     * that yields nothing do we fall back to GetBucketLocation, then a sane default.
     */
    private String regionFor(CloudAccount account, String bucket) {
        String cacheKey = account.getAwsAccountId() + ":" + bucket;
        return bucketRegionCache.computeIfAbsent(cacheKey, k -> {
            S3Client probe = clientFor(account, "us-east-1");

            // 1) HeadBucket — read x-amz-bucket-region from the response or the redirect error.
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

            // 2) GetBucketLocation (empty constraint == us-east-1).
            try {
                String loc = probe.getBucketLocation(b -> b.bucket(bucket)).locationConstraintAsString();
                if (loc != null && !loc.isBlank()) return loc;
                return "us-east-1";
            } catch (Exception e) {
                log.warn("[AccountS3] Could not resolve region for bucket {} (defaulting ap-south-1): {}",
                        bucket, e.getMessage());
                return "ap-south-1";
            }
        });
    }

    private static final java.util.regex.Pattern EXPECTING_REGION =
            java.util.regex.Pattern.compile("expecting '([a-z0-9-]+)'");

    /** Extract the region S3 names in a wrong-region error message, if present. */
    private static String parseExpectedRegion(String message) {
        if (message == null) return null;
        java.util.regex.Matcher m = EXPECTING_REGION.matcher(message);
        return m.find() ? m.group(1) : null;
    }

    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry(".m3u8", "application/vnd.apple.mpegurl"),
            Map.entry(".ts",   "video/mp2t"),
            Map.entry(".mpd",  "application/dash+xml"),
            Map.entry(".m4s",  "video/mp4"),
            Map.entry(".mp4",  "video/mp4"),
            Map.entry(".mov",  "video/quicktime"),
            Map.entry(".webm", "video/webm"),
            Map.entry(".mkv",  "video/x-matroska"),
            Map.entry(".mp3",  "audio/mpeg"),
            Map.entry(".aac",  "audio/aac"),
            Map.entry(".flac", "audio/flac"),
            Map.entry(".ogg",  "audio/ogg"),
            Map.entry(".vtt",  "text/vtt"),
            Map.entry(".jpg",  "image/jpeg"),
            Map.entry(".jpeg", "image/jpeg"),
            Map.entry(".png",  "image/png"),
            Map.entry(".key",  "application/octet-stream")
    );

    private static String contentTypeFor(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        return CONTENT_TYPES.getOrDefault(filename.substring(dot).toLowerCase(), "application/octet-stream");
    }
}
