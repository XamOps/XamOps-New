package com.xammer.billops.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
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

    /**
     * Uploads a MultipartFile (from HTTP request) to S3.
     */
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
                    .contentType(file.getContentType()) // Store the correct MIME type
                    .build();

            s3Client.putObject(putOb, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
            return key;
        } catch (IOException e) {
            logger.error("Failed to upload file to S3", e);
            throw new RuntimeException("Failed to upload attachment", e);
        }
    }

    /**
     * NEW METHOD: Uploads raw byte content (e.g., generated PDFs) to S3.
     */
    public String uploadFileBytes(String fileName, byte[] content, String contentType) {
        // Create a unique key specific for RCA documents
        String key = "rca/" + UUID.randomUUID() + "/" + fileName;

        try {
            PutObjectRequest putOb = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType(contentType)
                    .build();

            s3Client.putObject(putOb, RequestBody.fromBytes(content));
            return key;
        } catch (Exception e) {
            logger.error("Failed to upload byte content to S3", e);
            throw new RuntimeException("Failed to upload file content", e);
        }
    }

    /** Strip non-ISO-8859-1 characters (e.g. macOS narrow no-break space U+202F) from filenames. */
    private String sanitizeFileName(String fileName) {
        return fileName.replaceAll("[^\\x00-\\xFF]", "_");
    }

    public String generatePresignedUrl(String key) {
        if (key == null || key.isBlank())
            return null;

        try {
            String fileName = key.substring(key.lastIndexOf("/") + 1);
            String safeFileName = sanitizeFileName(fileName);

            String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
<<<<<<< HEAD
                    .responseContentDisposition("inline; filename=\"" + safeFileName + "\"")
=======
                    .responseContentDisposition("inline; filename*=UTF-8''" + encodedFileName)
>>>>>>> 12adb541adafe26bff8ccb1b8b7063eb22e3bb24
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(15)) // Link valid for 15 mins
                    .getObjectRequest(getObjectRequest)
                    .build();

            return s3Presigner.presignGetObject(presignRequest).url().toString();
        } catch (Exception e) {
            logger.error("Failed to generate presigned URL", e);
            return null;
        }
    }
}