package com.xammer.cloud.repository;

import com.xammer.cloud.domain.UpwindPostureFinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the status filtering on posture queries.
 *
 * The sync now fetches PASS findings as well as FAIL (the Upwind endpoint defaults to FAIL,
 * which is why per-framework pass counts used to be structurally zero). That means this
 * table holds passing rows, and any query presenting findings AS PROBLEMS must exclude them
 * — otherwise passing checks surface in Configurations, Issues and Identities as failures.
 *
 * Scope note: {@code findDistinctFindings} and {@code findExposureFailings} are NOT covered
 * here. Both are native PostgreSQL queries (DISTINCT ON, jsonb_exists) that will not run on
 * the H2 slice database. Their filters were added by inspection and remain unverified by
 * automated tests.
 */
@DataJpaTest
@ContextConfiguration(classes = JpaSliceTestConfig.class)
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=false"
})
class UpwindPostureFindingRepositoryTest {

    private static final List<String> ACCOUNTS = List.of("605134457560");

    @Autowired
    private UpwindPostureFindingRepository repo;

    @BeforeEach
    void seed() {
        repo.deleteAll();
        // Same check, same framework, one failing resource and one passing resource.
        repo.save(finding("fnd-fail", "FAIL", "HIGH", "s3-public-bucket",
                "arn:aws:s3:::leaky", "AWS_S3_BUCKET"));
        repo.save(finding("fnd-pass", "PASS", "HIGH", "s3-public-bucket",
                "arn:aws:s3:::tidy", "AWS_S3_BUCKET"));
        // An identity row per status, for the Identities projection.
        repo.save(finding("fnd-id-fail", "FAIL", "MEDIUM", "mfa-missing",
                "arn:aws:iam::605134457560:user/kartik", "AWS_IAM_USER"));
        repo.save(finding("fnd-id-pass", "PASS", "MEDIUM", "mfa-missing",
                "arn:aws:iam::605134457560:user/priya", "AWS_IAM_USER"));
        repo.flush();
    }

    private static UpwindPostureFinding finding(String id, String status, String severity,
                                                String check, String resourceId, String type) {
        UpwindPostureFinding f = new UpwindPostureFinding();
        f.setUpwindId(id);
        f.setStatus(status);
        f.setSeverity(severity);
        f.setTitle(check);
        f.setCheckTitle(check);
        f.setFrameworkTitle("SOC2");
        f.setCloudProvider("AWS");
        f.setCloudAccountId("605134457560");
        f.setRegion("ap-south-1");
        f.setResourceId(resourceId);
        f.setResourceName(resourceId);
        f.setResourceType(type);
        f.setFirstSeenTime(Instant.parse("2026-07-01T00:00:00Z"));
        f.setLastSeenTime(Instant.parse("2026-08-01T00:00:00Z"));
        f.setSyncedAt(Instant.parse("2026-08-04T00:00:00Z"));
        return f;
    }

    @Test
    void issuesProjectionExcludesPassingFindings() {
        List<Object[]> rows = repo.findFailingForIssues(ACCOUNTS);
        assertThat(rows).hasSize(2);   // the two FAIL rows only
        assertThat(rows).allSatisfy(r ->
                assertThat((String) r[6]).doesNotContain("tidy").doesNotContain("priya"));
    }

    @Test
    void identityProjectionExcludesPassingFindings() {
        List<Object[]> rows = repo.findIdentityFailings(ACCOUNTS);
        assertThat(rows).hasSize(1);
        assertThat((String) rows.get(0)[0]).endsWith("user/kartik");
    }

    @Test
    void severityCountsCoverOnlyFailures() {
        List<Object[]> rows = repo.countFailBySeverity(ACCOUNTS);
        // One HIGH failure and one MEDIUM failure; the PASS rows must not be counted.
        assertThat(rows).hasSize(2);
        long total = rows.stream().mapToLong(r -> (Long) r[1]).sum();
        assertThat(total).isEqualTo(2);
    }

    @Test
    void resourceViewExcludesPassingFindings() {
        assertThat(repo.findFailingByResource(ACCOUNTS, "arn:aws:s3:::leaky")).hasSize(1);
        // A resource that only PASSES must look clean, not like an open finding.
        assertThat(repo.findFailingByResource(ACCOUNTS, "arn:aws:s3:::tidy")).isEmpty();
    }

    @Test
    void checkDrawerExcludesPassingFindings() {
        List<UpwindPostureFinding> rows = repo.findFailingByCheck(ACCOUNTS, "s3-public-bucket");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo("FAIL");
    }

    /**
     * The deliberate exception: compliance groups BY status, so it must see both. This is
     * what makes a real pass count possible — it was always zero before, because the sync
     * never fetched PASS rows.
     */
    @Test
    void frameworkCountsReportBothStatuses() {
        List<Object[]> rows = repo.countByFrameworkAndStatus(ACCOUNTS);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(r -> (String) r[1])
                .containsExactlyInAnyOrder("FAIL", "PASS");
        assertThat(rows).allSatisfy(r -> assertThat((Long) r[2]).isEqualTo(2L));
    }

    /** Reconciliation must archive stale rows of every status, not just failures. */
    @Test
    void markResolvedArchivesStaleRowsRegardlessOfStatus() {
        int archived = repo.markResolvedNotSyncedSince(Instant.parse("2026-08-05T00:00:00Z"));
        assertThat(archived).isEqualTo(4);
        assertThat(repo.findFailingForIssues(ACCOUNTS)).isEmpty();
        assertThat(repo.countByFrameworkAndStatus(ACCOUNTS))
                .as("everything is RESOLVED, so neither FAIL nor PASS remains")
                .allSatisfy(r -> assertThat((String) r[1]).isEqualTo("RESOLVED"));
    }
}
