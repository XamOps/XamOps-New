package com.xammer.billops.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class FileStorageService {

    private static final Logger logger = LoggerFactory.getLogger(FileStorageService.class);

    /** Above this size a single PUT is replaced by a multipart upload. */
    private static final long MULTIPART_THRESHOLD_BYTES = 100L * 1024 * 1024;

    /** 64MB parts: S3 allows 10,000 of them, so a single file can run to ~640GB. */
    private static final int PART_SIZE_BYTES = 64 * 1024 * 1024;

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
        return uploadFile(file, "tickets");
    }

    /**
     * Uploads a MultipartFile under the given key prefix (e.g. "tickets", "credits").
     */
    public String uploadFile(MultipartFile file, String keyPrefix) {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            originalFilename = "attachment";
        }

        String key = keyPrefix + "/" + UUID.randomUUID() + "/" + sanitizeFileName(originalFilename);

        try {
            if (file.getSize() > MULTIPART_THRESHOLD_BYTES) {
                uploadInParts(key, file);
                return key;
            }

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
     * Uploads a large file as a multipart upload. A single PUT is capped at 5GB by S3, and this
     * path also avoids re-sending the whole file when one part fails. A failed upload is aborted
     * so no partial parts are left accruing storage charges.
     */
    private void uploadInParts(String key, MultipartFile file) throws IOException {
        String uploadId = s3Client.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType(file.getContentType())
                .build()).uploadId();

        logger.info("Uploading {} ({} bytes) to S3 in parts", key, file.getSize());

        try (InputStream in = file.getInputStream()) {
            List<CompletedPart> completedParts = new ArrayList<>();
            byte[] buffer = new byte[PART_SIZE_BYTES];
            int partNumber = 1;
            int read;

            while ((read = readPart(in, buffer)) > 0) {
                UploadPartResponse response = s3Client.uploadPart(
                        UploadPartRequest.builder()
                                .bucket(bucketName)
                                .key(key)
                                .uploadId(uploadId)
                                .partNumber(partNumber)
                                .build(),
                        RequestBody.fromInputStream(new ByteArrayInputStream(buffer, 0, read), read));

                completedParts.add(CompletedPart.builder()
                        .partNumber(partNumber)
                        .eTag(response.eTag())
                        .build());
                partNumber++;
            }

            s3Client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                    .build());
        } catch (IOException | RuntimeException e) {
            logger.error("Multipart upload of {} failed - aborting", key, e);
            try {
                s3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                        .bucket(bucketName)
                        .key(key)
                        .uploadId(uploadId)
                        .build());
            } catch (RuntimeException abortFailure) {
                logger.warn("Could not abort multipart upload of {}: {}", key, abortFailure.getMessage());
            }
            throw e;
        }
    }

    /** Fills the buffer from the stream, returning how many bytes were read (0 at end of stream). */
    private static int readPart(InputStream in, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int read = in.read(buffer, total, buffer.length - total);
            if (read == -1) {
                break;
            }
            total += read;
        }
        return total;
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

    /**
     * Strip non-ISO-8859-1 characters (e.g. macOS narrow no-break space U+202F)
     * from filenames.
     */
    private String sanitizeFileName(String fileName) {
        return fileName.replaceAll("[^\\x00-\\xFF]", "_");
    }

    public String generatePresignedUrl(String key) {
        return generatePresignedUrl(key, true);
    }

    /**
     * @param inline true renders the file in the browser, false forces a download.
     */
    public String generatePresignedUrl(String key, boolean inline) {
        if (key == null || key.isBlank())
            return null;

        try {
            String fileName = key.substring(key.lastIndexOf("/") + 1);
            String safeFileName = sanitizeFileName(fileName);

            String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .responseContentDisposition(
                            (inline ? "inline" : "attachment") + "; filename=\"" + safeFileName + "\"")
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