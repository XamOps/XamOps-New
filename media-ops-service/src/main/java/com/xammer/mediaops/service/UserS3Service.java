package com.xammer.mediaops.service;

import com.xammer.mediaops.domain.UserS3Config;
import com.xammer.mediaops.dto.S3FileItem;
import com.xammer.mediaops.dto.UserS3ConnectRequest;
import com.xammer.mediaops.repository.UserS3ConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class UserS3Service {

    private static final Logger log = LoggerFactory.getLogger(UserS3Service.class);

    private static final Set<String> VIDEO_EXTENSIONS = new HashSet<>(Arrays.asList(
            ".mp4", ".mov", ".avi", ".mkv", ".webm", ".m4v", ".flv", ".wmv",
            ".mpeg", ".mpg", ".ts", ".mts", ".m2ts", ".3gp", ".ogv", ".rm", ".rmvb"
    ));

    private final UserS3ConfigRepository configRepo;

    public UserS3Service(UserS3ConfigRepository configRepo) {
        this.configRepo = configRepo;
    }

    @Transactional
    public UserS3Config connect(Long clientId, UserS3ConnectRequest req) {
        S3Client client = buildClient(req.getBucketName(), req.getRegion(),
                req.getAccessKey(), req.getSecretKey(), req.getEndpointUrl());
        try {
            // Validate credentials by checking bucket access
            client.headBucket(b -> b.bucket(req.getBucketName()));
        } catch (NoSuchBucketException e) {
            throw new IllegalArgumentException("Bucket not found: " + req.getBucketName());
        } catch (S3Exception e) {
            throw new IllegalArgumentException("Cannot access bucket: " + e.awsErrorDetails().errorMessage());
        } finally {
            client.close();
        }

        UserS3Config config = new UserS3Config();
        config.setClientId(clientId);
        config.setNickname(req.getNickname() != null && !req.getNickname().isBlank()
                ? req.getNickname() : req.getBucketName());
        config.setBucketName(req.getBucketName());
        config.setRegion(req.getRegion());
        config.setAccessKey(req.getAccessKey());
        config.setSecretKey(req.getSecretKey());
        config.setPrefixFilter(req.getPrefixFilter() != null ? req.getPrefixFilter().strip() : null);
        config.setEndpointUrl(req.getEndpointUrl());
        config.setCreatedAt(Instant.now());
        config.setUpdatedAt(Instant.now());
        return configRepo.save(config);
    }

    public List<UserS3Config> listConfigs(Long clientId) {
        return configRepo.findByClientId(clientId);
    }

    @Transactional
    public boolean deleteConfig(Long id, Long clientId) {
        return configRepo.findByIdAndClientId(id, clientId).map(c -> {
            configRepo.delete(c);
            return true;
        }).orElse(false);
    }

    public List<S3FileItem> listFiles(Long configId, Long clientId) {
        UserS3Config config = configRepo.findByIdAndClientId(configId, clientId)
                .orElseThrow(() -> new NoSuchElementException("S3 config not found"));

        S3Client client = buildClient(config.getBucketName(), config.getRegion(),
                config.getAccessKey(), config.getSecretKey(), config.getEndpointUrl());

        List<S3FileItem> items = new ArrayList<>();
        String prefix = config.getPrefixFilter() != null ? config.getPrefixFilter() : "";

        try {
            String continuationToken = null;
            do {
                ListObjectsV2Request.Builder reqBuilder = ListObjectsV2Request.builder()
                        .bucket(config.getBucketName())
                        .maxKeys(1000);
                if (!prefix.isBlank()) reqBuilder.prefix(prefix);
                if (continuationToken != null) reqBuilder.continuationToken(continuationToken);

                ListObjectsV2Response resp = client.listObjectsV2(reqBuilder.build());

                for (S3Object obj : resp.contents()) {
                    String key = obj.key();
                    if (key.endsWith("/")) continue; // skip folder markers

                    String ext = extension(key);
                    if (!VIDEO_EXTENSIONS.contains(ext)) continue;

                    S3FileItem item = new S3FileItem();
                    item.setKey(key);
                    item.setName(fileName(key));
                    item.setSizeBytes(obj.size());
                    item.setFormattedSize(formatBytes(obj.size()));
                    item.setLastModified(obj.lastModified());
                    item.setExtension(ext);
                    items.add(item);
                }

                continuationToken = resp.isTruncated() ? resp.nextContinuationToken() : null;
            } while (continuationToken != null);
        } finally {
            client.close();
        }

        items.sort(Comparator.comparing(S3FileItem::getLastModified).reversed());
        return items;
    }

    public String presignUrl(Long configId, Long clientId, String s3Key, int expiryMinutes) {
        UserS3Config config = configRepo.findByIdAndClientId(configId, clientId)
                .orElseThrow(() -> new NoSuchElementException("S3 config not found"));

        S3Presigner.Builder pb = S3Presigner.builder().region(Region.of(config.getRegion()));
        if (config.getAccessKey() != null && !config.getAccessKey().isBlank()) {
            pb.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(config.getAccessKey(), config.getSecretKey())));
        }
        if (config.getEndpointUrl() != null && !config.getEndpointUrl().isBlank()) {
            pb.endpointOverride(URI.create(config.getEndpointUrl()));
        }

        try (S3Presigner presigner = pb.build()) {
            GetObjectPresignRequest presignReq = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(expiryMinutes))
                    .getObjectRequest(r -> r.bucket(config.getBucketName()).key(s3Key))
                    .build();
            PresignedGetObjectRequest presigned = presigner.presignGetObject(presignReq);
            return presigned.url().toString();
        }
    }

    // ─── Output storage (transcoded files → customer bucket) ────────────────────

    /**
     * Verify the connected bucket is writable with the stored credentials by
     * writing and immediately deleting a tiny marker object. Throws
     * IllegalArgumentException with a human-readable message when the bucket
     * cannot be written (missing s3:PutObject, wrong bucket, expired keys, …),
     * so job submission can fail fast with a clear error instead of a job that
     * only breaks at the very end after transcoding.
     */
    public void verifyWritable(Long configId, Long clientId) {
        UserS3Config config = requireConfig(configId, clientId);
        S3Client client = buildClient(config.getBucketName(), config.getRegion(),
                config.getAccessKey(), config.getSecretKey(), config.getEndpointUrl());
        String testKey = OUTPUT_ROOT + "/.xamops-write-check-" + UUID.randomUUID();
        try {
            client.putObject(PutObjectRequest.builder().bucket(config.getBucketName()).key(testKey).build(),
                    RequestBody.fromString("xamops"));
            client.deleteObject(DeleteObjectRequest.builder().bucket(config.getBucketName()).key(testKey).build());
        } catch (S3Exception e) {
            throw new IllegalArgumentException("Cannot write to bucket '" + config.getBucketName()
                    + "': " + e.awsErrorDetails().errorMessage());
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot write to bucket '" + config.getBucketName()
                    + "': " + e.getMessage());
        } finally {
            client.close();
        }
    }

    /**
     * Upload every regular file under {@code localDir} to the customer bucket
     * beneath {@code keyPrefix}, preserving the relative directory layout (so an
     * HLS manifest and its segments keep their filenames and relative links).
     */
    public void uploadDirectory(Long configId, Long clientId, Path localDir, String keyPrefix) throws IOException {
        UserS3Config config = requireConfig(configId, clientId);
        S3Client client = buildClient(config.getBucketName(), config.getRegion(),
                config.getAccessKey(), config.getSecretKey(), config.getEndpointUrl());
        try (java.util.stream.Stream<Path> walk = Files.walk(localDir)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                String relative = localDir.relativize(file).toString().replace('\\', '/');
                String key = keyPrefix + "/" + relative;
                client.putObject(PutObjectRequest.builder()
                                .bucket(config.getBucketName())
                                .key(key)
                                .contentType(contentTypeFor(relative))
                                .build(),
                        RequestBody.fromFile(file));
                log.debug("[UserS3] Uploaded s3://{}/{}", config.getBucketName(), key);
            });
        } finally {
            client.close();
        }
    }

    /**
     * Stream a single output object from the customer bucket to an HTTP response,
     * honouring a {@code Range} request header so seeking and progressive playback
     * work. This is what makes ALL output formats — including HLS/DASH whose
     * manifests reference many relative segment files — playable while the
     * customer bucket stays private: the player fetches every file through this
     * same proxy path and never needs public access or per-segment presigning.
     */
    public void streamObject(Long configId, Long clientId, String key, String rangeHeader,
                             HttpServletResponse response) throws IOException {
        UserS3Config config = requireConfig(configId, clientId);
        S3Client client = buildClient(config.getBucketName(), config.getRegion(),
                config.getAccessKey(), config.getSecretKey(), config.getEndpointUrl());
        try {
            GetObjectRequest.Builder reqBuilder = GetObjectRequest.builder()
                    .bucket(config.getBucketName()).key(key);
            if (rangeHeader != null && !rangeHeader.isBlank()) {
                reqBuilder.range(rangeHeader);
            }
            ResponseInputStream<GetObjectResponse> in = client.getObject(reqBuilder.build());
            GetObjectResponse meta = in.response();

            response.setContentType(contentTypeFor(key));
            response.setHeader("Accept-Ranges", "bytes");
            if (meta.contentLength() != null) {
                response.setHeader("Content-Length", String.valueOf(meta.contentLength()));
            }
            if (rangeHeader != null && !rangeHeader.isBlank() && meta.contentRange() != null) {
                response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT); // 206
                response.setHeader("Content-Range", meta.contentRange());
            } else {
                response.setStatus(HttpServletResponse.SC_OK);
            }
            try (in) {
                in.transferTo(response.getOutputStream());
            }
        } catch (NoSuchKeyException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        } finally {
            client.close();
        }
    }

    /**
     * Write every object under {@code keyPrefix} into a ZIP stream, with entry
     * names relative to the prefix. Returns whether any object was written — used
     * to build the download archive for jobs whose output lives in a customer
     * bucket (and whose local copies may have been cleaned up).
     */
    public boolean writeObjectsUnderPrefix(Long configId, Long clientId, String keyPrefix,
                                           ZipOutputStream zip) throws IOException {
        UserS3Config config = requireConfig(configId, clientId);
        S3Client client = buildClient(config.getBucketName(), config.getRegion(),
                config.getAccessKey(), config.getSecretKey(), config.getEndpointUrl());
        boolean wrote = false;
        String normalizedPrefix = keyPrefix.endsWith("/") ? keyPrefix : keyPrefix + "/";
        try {
            String continuationToken = null;
            do {
                ListObjectsV2Request.Builder rb = ListObjectsV2Request.builder()
                        .bucket(config.getBucketName()).prefix(normalizedPrefix).maxKeys(1000);
                if (continuationToken != null) rb.continuationToken(continuationToken);
                ListObjectsV2Response resp = client.listObjectsV2(rb.build());
                for (S3Object obj : resp.contents()) {
                    if (obj.key().endsWith("/")) continue;
                    String entryName = obj.key().substring(normalizedPrefix.length());
                    if (entryName.isEmpty()) continue;
                    zip.putNextEntry(new ZipEntry(entryName));
                    try (ResponseInputStream<GetObjectResponse> in = client.getObject(
                            GetObjectRequest.builder().bucket(config.getBucketName()).key(obj.key()).build())) {
                        in.transferTo(zip);
                    }
                    zip.closeEntry();
                    wrote = true;
                }
                continuationToken = Boolean.TRUE.equals(resp.isTruncated()) ? resp.nextContinuationToken() : null;
            } while (continuationToken != null);
        } finally {
            client.close();
        }
        return wrote;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /** Root prefix under which all XamOps transcode output is written in a customer bucket. */
    public static final String OUTPUT_ROOT = MediaOutputPath.OUTPUT_ROOT;

    private UserS3Config requireConfig(Long configId, Long clientId) {
        return configRepo.findByIdAndClientId(configId, clientId)
                .orElseThrow(() -> new NoSuchElementException("S3 config not found"));
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
        return CONTENT_TYPES.getOrDefault(extension(filename), "application/octet-stream");
    }

    private S3Client buildClient(String bucket, String region, String accessKey, String secretKey, String endpointUrl) {
        S3ClientBuilder builder = S3Client.builder().region(Region.of(region));
        if (accessKey != null && !accessKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey)));
        }
        if (endpointUrl != null && !endpointUrl.isBlank()) {
            builder.endpointOverride(URI.create(endpointUrl))
                   .serviceConfiguration(s -> s.pathStyleAccessEnabled(true));
        }
        return builder.build();
    }

    private static String extension(String key) {
        int dot = key.lastIndexOf('.');
        if (dot < 0) return "";
        return key.substring(dot).toLowerCase();
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
