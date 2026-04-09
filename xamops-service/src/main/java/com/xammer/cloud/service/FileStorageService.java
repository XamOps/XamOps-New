package com.xammer.cloud.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

@Service
public class FileStorageService {

    private static final Logger logger = LoggerFactory.getLogger(FileStorageService.class);

    @Value("${app.s3.bucket-name}")
    private String bucketName;

    @Value("${app.s3.region}")
    private String region;

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    public FileStorageService(S3Client s3Client, S3Presigner s3Presigner) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
    }

    public String uploadFile(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            originalFilename = "attachment";
        }

        String key = "tickets/" + UUID.randomUUID() + "/" + sanitizeFileName(originalFilename);

        try {
            PutObjectRequest putOb = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType(file.getContentType()) // Store correct MIME type
                    .build();

            s3Client.putObject(putOb, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
            return key;
        } catch (IOException e) {
            logger.error("Failed to upload file to S3", e);
            throw new RuntimeException("Failed to upload attachment", e);
        }
    }

    public String generatePresignedUrl(String key) {
        if (key == null || key.isBlank())
            return null;

        try {
            String fileName = key.substring(key.lastIndexOf("/") + 1);
            String safeFileName = sanitizeFileName(fileName);

            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .responseContentDisposition("inline; filename=\"" + safeFileName + "\"")
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(15))
                    .getObjectRequest(getObjectRequest)
                    .build();

            return s3Presigner.presignGetObject(presignRequest).url().toString();
        } catch (Exception e) {
            logger.error("Failed to generate presigned URL", e);
            return null;
        }
    }

    /** Upload raw bytes to S3 at the given key. Used for generated CSV/PDF files. */
    public String uploadFileBytes(String key, byte[] content, String contentType) {
        PutObjectRequest putOb = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType(contentType)
                .build();
        s3Client.putObject(putOb, RequestBody.fromBytes(content));
        logger.info("Uploaded {} bytes to S3 key: {}", content.length, key);
        return key;
    }

    /**
     * Generate a presigned GET URL with a custom expiry duration.
     * Used for billing CSV download links (48-hour expiry).
     */
    public String generatePresignedUrlWithExpiry(String key, Duration expiry) {
        if (key == null || key.isBlank())
            return null;

        try {
            String fileName = key.substring(key.lastIndexOf("/") + 1);
            String safeFileName = sanitizeFileName(fileName);

            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .responseContentDisposition("attachment; filename=\"" + safeFileName + "\"")
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(expiry)
                    .getObjectRequest(getObjectRequest)
                    .build();

            return s3Presigner.presignGetObject(presignRequest).url().toString();
        } catch (Exception e) {
            logger.error("Failed to generate presigned URL with expiry for key {}: {}", key, e.getMessage());
            return null;
        }
    }

    /** Strip non-ISO-8859-1 characters (e.g. macOS narrow no-break space U+202F) from filenames. */
    private String sanitizeFileName(String fileName) {
        // Replace any non-ASCII characters with an underscore to stay ISO-8859-1 safe
        return fileName.replaceAll("[^\\x00-\\xFF]", "_");
    }

    /** Check whether a key exists in S3 without downloading the object. */
    public boolean s3KeyExists(String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (Exception e) {
            logger.warn("Could not check S3 key existence for {}: {}", key, e.getMessage());
            return false;
        }
    }
}