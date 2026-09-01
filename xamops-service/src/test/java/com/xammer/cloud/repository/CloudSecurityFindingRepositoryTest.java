package com.xammer.cloud.repository;

import com.xammer.cloud.domain.CloudSecurityFinding;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ContextConfiguration;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice test for the findings repository.
 *
 * <p>
 * The reason this exists rather than relying on the mocked service tests: several queries here
 * are hand-written JPQL, and invalid JPQL is not a test failure — it is a
 * {@code QuerySyntaxException} at context startup, which takes the whole application down. The
 * severity ordering in particular uses a {@code CASE} expression inside {@code ORDER BY}, which
 * is the kind of construct that either works or refuses to build the {@code EntityManager}.
 * Exercising each query once is what makes that a red test instead of a failed deploy.
 */
@DataJpaTest
@ContextConfiguration(classes = JpaSliceTestConfig.class)
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=false"
})
class CloudSecurityFindingRepositoryTest {

    @Autowired
    CloudSecurityFindingRepository repository;

    private static final Long ACCOUNT = 42L;
    /** A second connection, used to prove reads never cross tenants. */
    private static final Long OTHER_ACCOUNT = 99L;

    private CloudSecurityFinding finding(Long cloudAccountId, String findingId, String severity,
            String status, Instant firstSeen) {
        CloudSecurityFinding f = new CloudSecurityFinding();
        f.setCloudAccountId(cloudAccountId);
        f.setAwsAccountId("123456789012");
        f.setFindingId(findingId);
        f.setSource(CloudSecurityFinding.SOURCE_GUARDDUTY);
        f.setSeverity(severity);
        f.setTitle("Finding " + findingId);
        f.setStatus(status);
        f.setResourceArn("arn:aws:s3:::bucket-" + findingId);
        f.setRegion("us-east-1");
        f.setFirstSeenAt(firstSeen);
        f.setLastSeenAt(firstSeen);
        f.setCreatedAt(firstSeen);
        if (!CloudSecurityFinding.STATUS_ACTIVE.equals(status)) {
            f.setResolvedAt(firstSeen);
        }
        return repository.save(f);
    }

    /**
     * The ordering the first screen depends on. Sorting the severity strings alphabetically
     * would put CRITICAL after both HIGH and LOW, which is precisely backwards for a page whose
     * job is to show the worst thing first.
     */
    @Test
    void activeFindingsComeBackMostSevereFirst() {
        Instant now = Instant.now();
        finding(ACCOUNT, "low", "LOW", "ACTIVE", now);
        finding(ACCOUNT, "crit", "CRITICAL", "ACTIVE", now);
        finding(ACCOUNT, "med", "MEDIUM", "ACTIVE", now);
        finding(ACCOUNT, "high", "HIGH", "ACTIVE", now);
        finding(ACCOUNT, "info", "INFORMATIONAL", "ACTIVE", now);

        List<CloudSecurityFinding> active = repository.findActive(ACCOUNT);

        assertThat(active).extracting(CloudSecurityFinding::getSeverity)
                .containsExactly("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFORMATIONAL");
    }

    @Test
    void resolvedAndSuppressedFindingsAreExcludedFromTheActiveList() {
        Instant now = Instant.now();
        finding(ACCOUNT, "a", "HIGH", "ACTIVE", now);
        finding(ACCOUNT, "b", "HIGH", "RESOLVED", now);
        finding(ACCOUNT, "c", "HIGH", "SUPPRESSED", now);

        assertThat(repository.findActive(ACCOUNT)).extracting(CloudSecurityFinding::getFindingId)
                .containsExactly("a");
        assertThat(repository.countByCloudAccountIdAndStatus(ACCOUNT, "ACTIVE")).isEqualTo(1);
    }

    /**
     * Reads are scoped to the connection. On a findings table this is not a tidiness concern —
     * leaking across cloud_account rows would show one tenant another's vulnerabilities.
     */
    @Test
    void readsNeverCrossConnections() {
        Instant now = Instant.now();
        finding(ACCOUNT, "mine", "CRITICAL", "ACTIVE", now);
        finding(OTHER_ACCOUNT, "theirs", "CRITICAL", "ACTIVE", now);

        assertThat(repository.findActive(ACCOUNT)).extracting(CloudSecurityFinding::getFindingId)
                .containsExactly("mine");
        assertThat(repository.findActive(OTHER_ACCOUNT)).extracting(CloudSecurityFinding::getFindingId)
                .containsExactly("theirs");
        assertThat(repository.findByCloudAccountIdAndFindingId(ACCOUNT, "theirs")).isEmpty();
    }

    /** The CRITICAL/HIGH slice the Alerts page reads. */
    @Test
    void severityFilterReturnsOnlyTheRequestedBands() {
        Instant now = Instant.now();
        finding(ACCOUNT, "crit", "CRITICAL", "ACTIVE", now);
        finding(ACCOUNT, "high", "HIGH", "ACTIVE", now);
        finding(ACCOUNT, "med", "MEDIUM", "ACTIVE", now);

        List<CloudSecurityFinding> alertable =
                repository.findActiveBySeverityIn(ACCOUNT, List.of("CRITICAL", "HIGH"));

        assertThat(alertable).extracting(CloudSecurityFinding::getFindingId)
                .containsExactlyInAnyOrder("crit", "high");
    }

    @Test
    void countsBySeverityGroupOnlyActiveFindings() {
        Instant now = Instant.now();
        finding(ACCOUNT, "c1", "CRITICAL", "ACTIVE", now);
        finding(ACCOUNT, "c2", "CRITICAL", "ACTIVE", now);
        finding(ACCOUNT, "h1", "HIGH", "ACTIVE", now);
        finding(ACCOUNT, "gone", "CRITICAL", "RESOLVED", now);

        List<Object[]> counts = repository.countActiveBySeverity(ACCOUNT);

        assertThat(counts).hasSize(2);
        for (Object[] row : counts) {
            long value = ((Number) row[1]).longValue();
            if ("CRITICAL".equals(row[0])) {
                assertThat(value).isEqualTo(2);
            } else {
                assertThat(value).isEqualTo(1);
            }
        }
    }

    @Test
    void recentFindingsAreBoundedByFirstSeen() {
        Instant now = Instant.now();
        finding(ACCOUNT, "fresh", "HIGH", "ACTIVE", now);
        finding(ACCOUNT, "stale", "HIGH", "ACTIVE", now.minus(3, ChronoUnit.HOURS));

        List<CloudSecurityFinding> recent =
                repository.findRecent(ACCOUNT, now.minus(30, ChronoUnit.MINUTES));

        assertThat(recent).extracting(CloudSecurityFinding::getFindingId).containsExactly("fresh");
    }

    @Test
    void resourceDrillDownFindsEverythingAgainstOneArn() {
        Instant now = Instant.now();
        CloudSecurityFinding saved = finding(ACCOUNT, "r1", "HIGH", "ACTIVE", now);

        assertThat(repository.findByResource(ACCOUNT, saved.getResourceArn()))
                .extracting(CloudSecurityFinding::getFindingId).containsExactly("r1");
    }

    /**
     * Notification is driven off a query rather than the ingest call site, so a crash between
     * "row written" and "email sent" retries instead of losing the alert.
     */
    @Test
    void unnotifiedQueryFindsOnlyNeverNotifiedFindingsAtOrAboveTheThreshold() {
        Instant now = Instant.now();
        finding(ACCOUNT, "new-crit", "CRITICAL", "ACTIVE", now);
        CloudSecurityFinding alreadySent = finding(ACCOUNT, "sent-crit", "CRITICAL", "ACTIVE", now);
        alreadySent.setNotifiedAt(now);
        repository.save(alreadySent);
        finding(ACCOUNT, "low-noise", "LOW", "ACTIVE", now);

        List<CloudSecurityFinding> pending =
                repository.findUnnotified(ACCOUNT, List.of("CRITICAL", "HIGH"));

        assertThat(pending).extracting(CloudSecurityFinding::getFindingId)
                .containsExactly("new-crit");

        int marked = repository.markNotified(
                pending.stream().map(CloudSecurityFinding::getId).toList(), now);
        assertThat(marked).isEqualTo(1);
    }

    @Test
    void purgeRemovesOnlyResolvedFindingsPastRetention() {
        Instant now = Instant.now();
        finding(ACCOUNT, "keep-active", "HIGH", "ACTIVE", now);
        finding(ACCOUNT, "keep-recent", "HIGH", "RESOLVED", now);
        finding(ACCOUNT, "purge-old", "HIGH", "RESOLVED", now.minus(120, ChronoUnit.DAYS));

        int purged = repository.purgeResolvedBefore(now.minus(90, ChronoUnit.DAYS));

        assertThat(purged).isEqualTo(1);
        assertThat(repository.findAll()).extracting(CloudSecurityFinding::getFindingId)
                .containsExactlyInAnyOrder("keep-active", "keep-recent");
    }

    @Test
    void pagedActiveReadHonoursSeverityOrderAcrossPages() {
        Instant now = Instant.now();
        finding(ACCOUNT, "low", "LOW", "ACTIVE", now);
        finding(ACCOUNT, "crit", "CRITICAL", "ACTIVE", now);
        finding(ACCOUNT, "high", "HIGH", "ACTIVE", now);

        assertThat(repository.findActive(ACCOUNT, PageRequest.of(0, 2)).getContent())
                .extracting(CloudSecurityFinding::getSeverity)
                .containsExactly("CRITICAL", "HIGH");
        assertThat(repository.findActive(ACCOUNT, PageRequest.of(1, 2)).getContent())
                .extracting(CloudSecurityFinding::getSeverity)
                .containsExactly("LOW");
    }
}
