package com.xammer.cloud.xampulse.service.archive;

import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

/** S3 implementation. Never run against a real bucket in this repository's tests. */
@Component("xpS3ArchiveStore")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class S3ArchiveStore implements ArchiveStore {

    private final ArchiveRoleCredentialProvider provider;

    public S3ArchiveStore(ArchiveRoleCredentialProvider provider) {
        this.provider = provider;
    }

    /** Package-visible so the headers can be asserted: always SSE-KMS, with the customer key when set. */
    static PutObjectRequest putRequest(XpAccountConfig cfg, String key) {
        if (!key.startsWith(ArchiveKeyLayout.ROOT)) {
            throw new IllegalArgumentException("archive keys must be under " + ArchiveKeyLayout.ROOT);
        }
        PutObjectRequest.Builder b = PutObjectRequest.builder().bucket(cfg.getBucketName()).key(key)
                .serverSideEncryption(ServerSideEncryption.AWS_KMS);
        if (cfg.getKmsKeyArn() != null && !cfg.getKmsKeyArn().isBlank()) {
            b.ssekmsKeyId(cfg.getKmsKeyArn());
        }
        return b.build();
    }

    @Override
    public void putFile(XpAccountConfig cfg, String key, Path file) {
        provider.s3(cfg).putObject(putRequest(cfg, key), RequestBody.fromFile(file));
    }

    @Override
    public void putText(XpAccountConfig cfg, String key, String text) {
        provider.s3(cfg).putObject(putRequest(cfg, key), RequestBody.fromString(text, StandardCharsets.UTF_8));
    }

    @Override
    public Optional<String> getText(XpAccountConfig cfg, String key) {
        if (!key.startsWith(ArchiveKeyLayout.ROOT)) {
            throw new IllegalArgumentException("archive keys must be under " + ArchiveKeyLayout.ROOT);
        }
        try {
            return Optional.of(provider.s3(cfg).getObjectAsBytes(GetObjectRequest.builder().bucket(cfg.getBucketName()).key(key).build())
                    .asString(StandardCharsets.UTF_8));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }
}
