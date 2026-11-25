package com.xammer.cloud.xampulse.service.setup;

import com.xammer.cloud.service.AwsClientProvider;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.sts.model.StsException;

import java.nio.charset.StandardCharsets;

/**
 * Assumes the customer's archive role through the host credentials and writes one small object under
 * {@code xamops-xampulse/_verify/}. The role cannot delete, so the same key is overwritten each time and versions
 * expire with the lifecycle rule. The ExternalId and credentials are never logged.
 */
@Component("xpAwsArchiveAccessVerifier")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class AwsArchiveAccessVerifier implements ArchiveAccessVerifier {

    private static final Logger log = LoggerFactory.getLogger(AwsArchiveAccessVerifier.class);
    static final String KEY = "xamops-xampulse/_verify/connectivity-test";
    private final AwsClientProvider aws;

    public AwsArchiveAccessVerifier(AwsClientProvider aws) {
        this.aws = aws;
    }

    @Override
    public Result verify(XpAccountConfig c) {
        if (c.getArchiveRoleArn() == null || c.getArchiveRoleArn().isBlank() || c.getBucketName() == null) {
            return new Result(false, false, "The archive role is not known yet.");
        }
        String region = c.getBucketRegion() == null ? c.getHomeRegion() : c.getBucketRegion();
        try {
            S3Client s3 = aws.getRoleS3Client(c.getArchiveRoleArn(), c.getArchiveExternalIdEnc(), region);
            var put = software.amazon.awssdk.services.s3.model.PutObjectRequest.builder()
                    .bucket(c.getBucketName()).key(KEY).serverSideEncryption(ServerSideEncryption.AWS_KMS);
            if (c.getKmsKeyArn() != null && !c.getKmsKeyArn().isBlank()) {
                put.ssekmsKeyId(c.getKmsKeyArn());
            }
            s3.putObject(put.build(), RequestBody.fromString("xamops xampulse connectivity test", StandardCharsets.UTF_8));
            return new Result(true, true, "ok");
        } catch (StsException e) {
            log.info("XamPulse archive role could not be assumed for account {}", c.getCloudAccountId());
            return new Result(false, false, "Role assumption failed");
        } catch (S3Exception e) {
            log.info("XamPulse archive write test failed for account {}: {}", c.getCloudAccountId(),
                    e.awsErrorDetails() == null ? e.statusCode() : e.awsErrorDetails().errorCode());
            // The role was assumed (we reached S3), the write was refused.
            return new Result(true, false, e.awsErrorDetails() == null ? "Write refused" : e.awsErrorDetails().errorCode());
        } catch (RuntimeException e) {
            // Credential provider failures from a failed assume-role surface as SdkClientException.
            log.info("XamPulse archive verification failed for account {}: {}", c.getCloudAccountId(), e.getClass().getSimpleName());
            return new Result(false, false, "Role assumption failed");
        }
    }
}
