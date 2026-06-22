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
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

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

    // ─── Helpers ──────────────────────────────────────────────────────────────

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
