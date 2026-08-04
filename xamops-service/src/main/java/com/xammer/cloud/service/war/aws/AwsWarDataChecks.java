package com.xammer.cloud.service.war.aws;

import com.xammer.cloud.dto.war.WarCheckResult;
import com.xammer.cloud.service.AwsClientProvider;
import com.xammer.cloud.service.war.WarScanContext;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.KeyManagerType;
import software.amazon.awssdk.services.macie2.Macie2Client;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;

import java.util.ArrayList;
import java.util.List;

/**
 * Data protection controls (advisory rows 28-37).
 *
 * <p>
 * The advisory frames these in billing terms: bulk exfiltration shows up as a
 * data-transfer spike that often fires before any security alert does, and a
 * ransomware event on an unversioned bucket turns into a rebuild-or-pay decision.
 * These checks cover the openings that make either outcome cheap for an attacker.
 */
@Component
public class AwsWarDataChecks {

    /**
     * Per-region bucket inspection cap. Each bucket costs four extra API calls
     * (public-access block, versioning, object lock, encryption), so an account with
     * thousands of buckets would otherwise turn a page load into a rate-limit event.
     * The sample is still enough to prove a systemic gap exists.
     */
    private static final int BUCKET_SAMPLE_LIMIT = 120;

    private final AwsClientProvider clients;

    public AwsWarDataChecks(AwsClientProvider clients) {
        this.clients = clients;
    }

    public void run(WarScanContext ctx) {
        String primaryRegion = ctx.regions().isEmpty() ? "us-east-1" : ctx.regions().get(0);
        checkS3Posture(ctx, primaryRegion);
        checkMacie(ctx, primaryRegion);
        for (String region : ctx.regions()) {
            checkEbsEncryption(ctx, region);
            checkKmsKeyRotation(ctx, region);
        }
    }

    /** AWS-32 (block public access), AWS-33 (versioning), AWS-34 (object lock), AWS-28 (KMS). */
    private void checkS3Posture(WarScanContext ctx, String region) {
        ctx.guard("s3:ListAllMyBuckets",
                List.of("AWS-32", "AWS-33", "AWS-34", "AWS-28"), () -> {
                    S3Client s3 = clients.getS3Client(ctx.account(), region);
                    List<Bucket> buckets = s3.listBuckets().buckets();

                    if (buckets.isEmpty()) {
                        List.of("AWS-32", "AWS-33", "AWS-34", "AWS-28").forEach(id -> ctx.put(id,
                                WarCheckResult.notApplicable("No S3 buckets in this account")));
                        return;
                    }

                    List<String> notBlocked = new ArrayList<>();
                    List<String> unversioned = new ArrayList<>();
                    List<String> noObjectLock = new ArrayList<>();
                    List<String> notKmsEncrypted = new ArrayList<>();

                    List<Bucket> sample = buckets.size() > BUCKET_SAMPLE_LIMIT
                            ? buckets.subList(0, BUCKET_SAMPLE_LIMIT)
                            : buckets;

                    for (Bucket bucket : sample) {
                        String name = bucket.name();

                        // A missing configuration throws NoSuchPublicAccessBlockConfiguration,
                        // which is itself the finding: nothing is blocking public access.
                        boolean allBlocked = ctx.quiet(false, () -> {
                            var cfg = s3.getPublicAccessBlock(r -> r.bucket(name))
                                    .publicAccessBlockConfiguration();
                            return Boolean.TRUE.equals(cfg.blockPublicAcls())
                                    && Boolean.TRUE.equals(cfg.ignorePublicAcls())
                                    && Boolean.TRUE.equals(cfg.blockPublicPolicy())
                                    && Boolean.TRUE.equals(cfg.restrictPublicBuckets());
                        });
                        if (!allBlocked) {
                            notBlocked.add(name + " — Block Public Access is not fully enabled");
                        }

                        boolean versioned = ctx.quiet(false, () -> "Enabled".equalsIgnoreCase(
                                s3.getBucketVersioning(r -> r.bucket(name)).statusAsString()));
                        if (!versioned) {
                            unversioned.add(name + " — versioning is off, no recovery from overwrite or "
                                    + "ransomware");
                        }

                        boolean objectLock = ctx.quiet(false, () -> "Enabled".equalsIgnoreCase(
                                s3.getObjectLockConfiguration(r -> r.bucket(name))
                                        .objectLockConfiguration().objectLockEnabledAsString()));
                        if (!objectLock) {
                            noObjectLock.add(name + " — no Object Lock retention");
                        }

                        boolean kms = ctx.quiet(false, () -> s3.getBucketEncryption(r -> r.bucket(name))
                                .serverSideEncryptionConfiguration().rules().stream()
                                .anyMatch(rule -> rule.applyServerSideEncryptionByDefault() != null
                                        && "aws:kms".equalsIgnoreCase(rule
                                                .applyServerSideEncryptionByDefault()
                                                .sseAlgorithmAsString())));
                        if (!kms) {
                            notKmsEncrypted.add(name + " — not encrypted with a KMS key");
                        }
                    }

                    ctx.put("AWS-32", WarCheckResult.of(notBlocked,
                                    "Buckets are missing full S3 Block Public Access",
                                    "Every bucket has all four Block Public Access settings on")
                            .withScanned(sample.size()));

                    ctx.put("AWS-33", WarCheckResult.of(unversioned,
                                    "Buckets have versioning disabled",
                                    "All sampled buckets have versioning enabled")
                            .withScanned(sample.size()));

                    // Object Lock is a deliberate choice for log/backup buckets, not a
                    // blanket requirement, so its absence is a warning rather than a failure.
                    ctx.put("AWS-34", noObjectLock.size() == sample.size()
                            ? WarCheckResult.warn("No bucket uses Object Lock — audit logs and backups have "
                                    + "no ransomware-resistant retention").withEvidence(noObjectLock)
                                    .withScanned(sample.size())
                            : WarCheckResult.pass("Object Lock is in use on at least one bucket")
                                    .withScanned(sample.size()));

                    ctx.put("AWS-28", WarCheckResult.of(notKmsEncrypted,
                                    "Buckets are not encrypted with customer managed KMS keys",
                                    "Sampled buckets use KMS encryption")
                            .withScanned(sample.size()));
                });
    }

    /** AWS-37: EBS encryption by default, plus any volume that slipped through unencrypted. */
    private void checkEbsEncryption(WarScanContext ctx, String region) {
        ctx.guardRegion(region, "ec2:GetEbsEncryptionByDefault",
                List.of("AWS-37"), () -> {
            Ec2Client ec2 = clients.getEc2Client(ctx.account(), region);
            boolean defaultOn = Boolean.TRUE.equals(ec2.getEbsEncryptionByDefault(r -> {
            }).ebsEncryptionByDefault());

            List<String> evidence = new ArrayList<>();
            if (!defaultOn) {
                evidence.add(region + " — EBS encryption by default is off");
            }
            ctx.quiet(null, () -> {
                ec2.describeVolumesPaginator().volumes().stream()
                        .filter(v -> !Boolean.TRUE.equals(v.encrypted()))
                        .limit(50)
                        .forEach(v -> evidence.add(String.format(
                                "Volume %s in %s is unencrypted", v.volumeId(), region)));
                return null;
            });

            ctx.merge("AWS-37", WarCheckResult.of(evidence,
                    "EBS data is not encrypted by default",
                    "EBS encryption by default is on and no unencrypted volumes were found")
                    .withRegion(region));
        });
    }

    /** AWS-29: automatic rotation on customer managed KMS keys. */
    private void checkKmsKeyRotation(WarScanContext ctx, String region) {
        ctx.guardRegion(region, "kms:GetKeyRotationStatus",
                List.of("AWS-29"), () -> {
            KmsClient kms = clients.getKmsClient(ctx.account(), region);
            List<String> unrotated = new ArrayList<>();
            int customerKeys = 0;

            for (var key : kms.listKeysPaginator().keys()) {
                var metadata = ctx.quiet(null, () -> kms
                        .describeKey(r -> r.keyId(key.keyId())).keyMetadata());
                if (metadata == null
                        || metadata.keyManager() != KeyManagerType.CUSTOMER
                        || !Boolean.TRUE.equals(metadata.enabled())) {
                    continue;
                }
                customerKeys++;
                boolean rotating = ctx.quiet(true, () -> kms
                        .getKeyRotationStatus(r -> r.keyId(key.keyId())).keyRotationEnabled());
                if (!rotating) {
                    unrotated.add(String.format("Key %s in %s has automatic rotation disabled",
                            key.keyId(), region));
                }
            }

            if (customerKeys == 0) {
                ctx.merge("AWS-29", WarCheckResult.notApplicable(
                        "No enabled customer managed KMS keys in " + region));
                return;
            }
            ctx.merge("AWS-29", WarCheckResult.of(unrotated,
                            "Customer managed KMS keys are not rotating automatically",
                            "All customer managed keys rotate automatically")
                    .withScanned(customerKeys).withRegion(region));
        });
    }

    /** AWS-36: Macie discovers the sensitive data an attacker would target first. */
    private void checkMacie(WarScanContext ctx, String region) {
        ctx.guard("macie2:GetMacieSession", List.of("AWS-36"), () -> {
            Macie2Client macie = clients.getMacie2Client(ctx.account(), region);
            String status = macie.getMacieSession(r -> {
            }).statusAsString();

            ctx.put("AWS-36", "ENABLED".equalsIgnoreCase(status)
                    ? WarCheckResult.pass("Macie is enabled and classifying S3 data")
                    : WarCheckResult.fail("Macie is not enabled (" + status + ") — sensitive-data exposure "
                            + "is remediated only after it is exfiltrated"));
        });
    }
}
