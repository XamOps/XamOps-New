package com.xammer.cloud.xampulse.service.archive;

import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class ArchiveLayoutAndStoreTest {

    private static final Instant HOUR = Instant.parse("2026-11-02T09:00:00Z");

    @Test
    void keysArePartitionedHiveStyleUnderTheProtectedPrefixWithNoFleetNames() {
        String key = ArchiveKeyLayout.logKey("123456789012", "ap-south-1", HOUR, "n1", 0);
        assertEquals("xamops-xampulse/v1/logs/account=123456789012/region=ap-south-1/year=2026/month=11/day=02/hour=09/part-n1-0.parquet", key);
        assertTrue(key.startsWith(ArchiveKeyLayout.ROOT));
        assertFalse(key.contains("i-") || key.contains("service="), "no instance or service in the key");
        assertEquals(key, ArchiveKeyLayout.logKey("123456789012", "ap-south-1", HOUR, "n1", 0), "deterministic so retries overwrite");
        assertEquals("xamops-xampulse/v1/_manifest/logs/account=123456789012/dt=2026-11-02.json",
                ArchiveKeyLayout.manifestKey("123456789012", LocalDate.of(2026, 11, 2)));
    }

    @Test
    void keyInputsAreValidatedSoNothingCanEscapeThePrefix() {
        assertThrows(IllegalArgumentException.class, () -> ArchiveKeyLayout.logKey("12345", "ap-south-1", HOUR, "n1", 0));
        assertThrows(IllegalArgumentException.class, () -> ArchiveKeyLayout.logKey("123456789012", "../x", HOUR, "n1", 0));
        assertThrows(IllegalArgumentException.class, () -> ArchiveKeyLayout.logKey("123456789012", "ap-south-1", HOUR, "n/1", 0));
        assertThrows(IllegalArgumentException.class, () -> ArchiveKeyLayout.logKey("123456789012", "ap-south-1", HOUR, "n1", -1));
    }

    @Test
    void putsAreAlwaysSseKmsAndUseTheCustomerKeyWhenSet() {
        XpAccountConfig cfg = new XpAccountConfig();
        cfg.setBucketName("my-logs");
        String key = ArchiveKeyLayout.logKey("123456789012", "ap-south-1", HOUR, "n1", 0);
        PutObjectRequest plain = S3ArchiveStore.putRequest(cfg, key);
        assertEquals(ServerSideEncryption.AWS_KMS, plain.serverSideEncryption());
        assertNull(plain.ssekmsKeyId());
        cfg.setKmsKeyArn("arn:aws:kms:ap-south-1:123456789012:key/abc");
        PutObjectRequest withKey = S3ArchiveStore.putRequest(cfg, key);
        assertEquals(ServerSideEncryption.AWS_KMS, withKey.serverSideEncryption());
        assertEquals("arn:aws:kms:ap-south-1:123456789012:key/abc", withKey.ssekmsKeyId());
        assertEquals("my-logs", withKey.bucket());
    }

    @Test
    void writesOutsideTheArchivePrefixAreRefused() {
        XpAccountConfig cfg = new XpAccountConfig();
        cfg.setBucketName("my-logs");
        assertThrows(IllegalArgumentException.class, () -> S3ArchiveStore.putRequest(cfg, "other/key"));
        assertThrows(IllegalArgumentException.class, () -> S3ArchiveStore.putRequest(cfg, "xamops-xampulse/_verify/x"));
    }
}
