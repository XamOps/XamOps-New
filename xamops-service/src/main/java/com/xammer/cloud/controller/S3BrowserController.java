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
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/s3-browser")
public class S3BrowserController {

    private static final Logger log = LoggerFactory.getLogger(S3BrowserController.class);

    private static final Set<String> VIDEO_EXTENSIONS = new HashSet<>(Arrays.asList(
            ".mp4", ".mov", ".avi", ".mkv", ".webm", ".m4v", ".flv", ".wmv",
            ".mpeg", ".mpg", ".ts", ".mts", ".m2ts", ".3gp", ".ogv"
    ));

    private final CloudAccountRepository accountRepo;
    private final AwsClientProvider awsClientProvider;
    private final StsClient stsClient;

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
                    String ext = extension(key);
                    if (!VIDEO_EXTENSIONS.contains(ext)) continue;

                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("key", key);
                    item.put("name", fileName(key));
                    item.put("sizeBytes", obj.size());
                    item.put("formattedSize", formatBytes(obj.size()));
                    item.put("lastModified", obj.lastModified() != null ? obj.lastModified().toString() : null);
                    item.put("extension", ext);
                    item.put("bucket", bucket);
                    item.put("region", region);
                    items.add(item);
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
            S3Presigner presigner = S3Presigner.builder()
                    .region(Region.of(region))
                    .credentialsProvider(creds)
                    .build();

            String url;
            try (presigner) {
                url = presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofMinutes(expiry))
                        .getObjectRequest(r -> r.bucket(bucket).key(key))
                        .build()).url().toString();
            }

            return ResponseEntity.ok(Map.of("url", url, "expiresInMinutes", expiry));
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

    private String getBucketRegion(CloudAccount account, String bucket) {
        try {
            S3Client s3 = awsClientProvider.getS3Client(account, "us-east-1");
            GetBucketLocationResponse loc = s3.getBucketLocation(r -> r.bucket(bucket));
            BucketLocationConstraint constraint = loc.locationConstraint();
            return (constraint == null || constraint == BucketLocationConstraint.UNKNOWN_TO_SDK_VERSION
                    || "".equals(constraint.toString()))
                    ? "us-east-1"
                    : constraint.toString();
        } catch (Exception e) {
            return "ap-south-1";
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

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
