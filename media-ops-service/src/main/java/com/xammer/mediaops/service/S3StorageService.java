package com.xammer.mediaops.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.Map;

@Service
public class S3StorageService {

    private static final Logger log = LoggerFactory.getLogger(S3StorageService.class);

    @Value("${mediaops.s3.enabled:false}")
    private boolean enabled;

    @Value("${mediaops.s3.bucket:}")
    private String bucket;

    @Value("${mediaops.s3.region:us-east-1}")
    private String region;

    @Value("${mediaops.s3.access-key:}")
    private String accessKey;

    @Value("${mediaops.s3.secret-key:}")
    private String secretKey;

    // Optional: override endpoint for MinIO / LocalStack
    @Value("${mediaops.s3.endpoint:}")
    private String endpoint;

    // Base URL for public access — S3 virtual-hosted or CloudFront
    // e.g. https://my-bucket.s3.ap-south-1.amazonaws.com
    //   or https://d1234abcd.cloudfront.net
    @Value("${mediaops.s3.public-base-url:}")
    private String publicBaseUrl;

    // Key prefix inside the bucket, e.g. "media-ops"
    @Value("${mediaops.s3.key-prefix:media-ops}")
    private String keyPrefix;

    @Value("${mediaops.s3.delete-local-after-upload:false}")
    private boolean deleteLocalAfterUpload;

    private S3Client s3;

    private static final Map<String, String> CONTENT_TYPES = Map.of(
        ".m3u8", "application/vnd.apple.mpegurl",
        ".ts",   "video/mp2t",
        ".mpd",  "application/dash+xml",
        ".m4s",  "video/iso.segment",
        ".mp4",  "video/mp4",
        ".webm", "video/webm",
        ".mkv",  "video/x-matroska"
    );

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("[S3Storage] Disabled — transcoded files stay on local disk.");
            return;
        }
        try {
            S3ClientBuilder builder = S3Client.builder().region(Region.of(region));

            // Use explicit keys when provided; otherwise fall back to the default
            // credential chain (IAM role, env vars AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY,
            // ~/.aws/credentials) — same mechanism as the rest of XamOps services.
            if (!accessKey.isBlank() && !secretKey.isBlank()) {
                builder.credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)));
            } else {
                builder.credentialsProvider(DefaultCredentialsProvider.create());
            }

            if (!endpoint.isBlank()) {
                builder.endpointOverride(URI.create(endpoint))
                       .serviceConfiguration(s -> s.pathStyleAccessEnabled(true));
            }
            s3 = builder.build();
            log.info("[S3Storage] Connected — bucket={} region={} credentials={}",
                    bucket, region, accessKey.isBlank() ? "default-chain" : "static-keys");
        } catch (Exception e) {
            log.error("[S3Storage] Failed to initialise S3 client: {}", e.getMessage());
            enabled = false;
        }
    }

    public boolean isEnabled() {
        return enabled && s3 != null;
    }

    public boolean isDeleteLocalAfterUpload() {
        return deleteLocalAfterUpload;
    }

    /**
     * Upload every file in {@code localDir} to S3 under
     * {@code <keyPrefix>/jobs/<jobId>/} and return the public base URL
     * for that job (without trailing slash).
     */
    public String uploadJobOutput(long jobId, Path localDir) throws IOException {
        String jobKey = keyPrefix + "/jobs/" + jobId;

        Files.walk(localDir)
             .filter(Files::isRegularFile)
             .forEach(file -> {
                 String relative = localDir.relativize(file).toString();
                 String key = jobKey + "/" + relative;
                 try {
                     PutObjectRequest req = PutObjectRequest.builder()
                             .bucket(bucket)
                             .key(key)
                             .contentType(contentTypeFor(file.getFileName().toString()))
                             .build();
                     s3.putObject(req, RequestBody.fromFile(file));
                     log.debug("[S3Storage] Uploaded s3://{}/{}", bucket, key);
                 } catch (Exception e) {
                     log.error("[S3Storage] Upload failed for {}: {}", key, e.getMessage());
                     throw new RuntimeException(e);
                 }
             });

        String baseUrl = publicBaseUrl.isBlank()
                ? "https://" + bucket + ".s3." + region + ".amazonaws.com"
                : publicBaseUrl;

        String outputUrl = baseUrl.stripTrailing() + "/" + jobKey;
        log.info("[S3Storage] Job {} uploaded → {}", jobId, outputUrl);

        if (deleteLocalAfterUpload) {
            deleteDirectory(localDir);
        }

        return outputUrl;
    }

    private String contentTypeFor(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        return CONTENT_TYPES.getOrDefault(filename.substring(dot).toLowerCase(), "application/octet-stream");
    }

    private void deleteDirectory(Path dir) {
        try {
            Files.walk(dir)
                 .sorted(java.util.Comparator.reverseOrder())
                 .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        } catch (IOException e) {
            log.warn("[S3Storage] Could not delete local dir {}: {}", dir, e.getMessage());
        }
    }
}
