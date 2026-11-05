package com.xammer.cloud.xampulse.service.archive;

import com.xammer.cloud.service.AwsClientProvider;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Gives an S3 client that acts as the customer's {@code XamOpsXamPulseArchiveRole}. The role is assumed with the
 * archive ExternalId (stored encrypted, decrypted by the entity converter) through the host's own credentials.
 * {@link AwsClientProvider} owns the STS session cache, which refreshes before expiry; this class adds no second cache.
 * Credentials are never passed to ClickHouse and never logged.
 */
@Component("xpArchiveRoleCredentialProvider")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class ArchiveRoleCredentialProvider {

    private final AwsClientProvider aws;

    public ArchiveRoleCredentialProvider(AwsClientProvider aws) {
        this.aws = aws;
    }

    public S3Client s3(XpAccountConfig cfg) {
        if (cfg.getArchiveRoleArn() == null || cfg.getArchiveRoleArn().isBlank() || cfg.getBucketName() == null || cfg.getBucketName().isBlank()) {
            throw new IllegalStateException("archive role or bucket is not known yet");
        }
        String region = cfg.getBucketRegion() == null || cfg.getBucketRegion().isBlank() ? cfg.getHomeRegion() : cfg.getBucketRegion();
        return aws.getRoleS3Client(cfg.getArchiveRoleArn(), cfg.getArchiveExternalIdEnc(), region);
    }

    /** Short-lived credentials of the archive role, for the one place that must hand them to ClickHouse's s3() reader. */
    public record RoleCredentials(String accessKeyId, String secretAccessKey, String sessionToken) {}

    public RoleCredentials credentials(XpAccountConfig cfg) {
        if (cfg.getArchiveRoleArn() == null || cfg.getArchiveRoleArn().isBlank()) {
            throw new IllegalStateException("archive role is not known yet");
        }
        var c = aws.getRoleCredentialsProvider(cfg.getArchiveRoleArn(), cfg.getArchiveExternalIdEnc()).resolveCredentials();
        String token = c instanceof software.amazon.awssdk.auth.credentials.AwsSessionCredentials sc ? sc.sessionToken() : "";
        return new RoleCredentials(c.accessKeyId(), c.secretAccessKey(), token);
    }
}
