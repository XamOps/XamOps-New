package com.xammer.cloud.repository;

import com.xammer.cloud.domain.SecurityFindingResolution;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice test for the resolution repository.
 *
 * <p>
 * Exists for the same reason {@link CloudSecurityFindingRepositoryTest} does: every query here is
 * hand-written JPQL, and invalid JPQL is not a test failure but a refusal to build the
 * {@code EntityManager} — the whole application fails to start. The bulk {@code UPDATE ... WHERE
 * findingKeyHash IN :hashes} is the risky one, since a {@code @Modifying} query with a collection
 * parameter either compiles or takes the context down with it.
 *
 * <p>
 * The tenant-scoping assertions are not ceremony either. On this table a scoping mistake
 * <em>hides</em> a live finding: one tenant dismissing a control on a shared AWS account would
 * blank it from another tenant's Security Center, which is the failure mode that would take
 * longest for anyone to notice.
 */
@DataJpaTest
@ContextConfiguration(classes = JpaSliceTestConfig.class)
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=false"
})
class SecurityFindingResolutionRepositoryTest {

    @Autowired
    SecurityFindingResolutionRepository repository;

    private static final Long ACCOUNT = 42L;
    /** A second connection, used to prove a dismissal never crosses tenants. */
    private static final Long OTHER_ACCOUNT = 99L;

    private SecurityFindingResolution resolution(Long cloudAccountId, String hash, Instant when) {
        SecurityFindingResolution r = new SecurityFindingResolution();
        r.setCloudAccountId(cloudAccountId);
        r.setFindingKeyHash(hash);
        r.setResourceId("arn:aws:s3:::bucket-" + hash);
        r.setControlId("S3.8");
        r.setSeverity("CRITICAL");
        r.setTitle("Bucket ACL grants public access");
        r.setResolvedBy("alice@example.com");
        r.setResolvedAt(when);
        return repository.save(r);
    }

    @Test
    void findLiveReturnsOnlyUnretiredRowsForTheAccount() {
        Instant now = Instant.now();
        resolution(ACCOUNT, "aaaa", now);
        SecurityFindingResolution retired = resolution(ACCOUNT, "bbbb", now);
        retired.setRetiredAt(now);
        retired.setRetiredReason(SecurityFindingResolution.RETIRED_STILL_PRESENT_IN_SCAN);
        repository.save(retired);
        resolution(OTHER_ACCOUNT, "cccc", now);

        List<SecurityFindingResolution> live = repository.findLive(ACCOUNT);

        assertThat(live).extracting(SecurityFindingResolution::getFindingKeyHash)
                .containsExactly("aaaa");
    }

    @Test
    void findLiveByHashIgnoresARetiredRow() {
        Instant now = Instant.now();
        SecurityFindingResolution r = resolution(ACCOUNT, "dddd", now);

        assertThat(repository.findLiveByHash(ACCOUNT, "dddd")).isPresent();
        // Another tenant dismissing the same finding must not satisfy this lookup.
        assertThat(repository.findLiveByHash(OTHER_ACCOUNT, "dddd")).isEmpty();

        r.setRetiredAt(now);
        r.setRetiredReason(SecurityFindingResolution.RETIRED_RESTORED_BY_USER);
        repository.save(r);

        assertThat(repository.findLiveByHash(ACCOUNT, "dddd")).isEmpty();
    }

    @Test
    void retireMarksTheNamedHashesAndLeavesTheRest() {
        Instant now = Instant.now();
        resolution(ACCOUNT, "eeee", now);
        resolution(ACCOUNT, "ffff", now);
        resolution(OTHER_ACCOUNT, "eeee", now);

        int retired = repository.retire(ACCOUNT, List.of("eeee"),
                SecurityFindingResolution.RETIRED_REOPENED_BY_PROVIDER, now);

        assertThat(retired).isEqualTo(1);
        assertThat(repository.findLive(ACCOUNT)).extracting(
                SecurityFindingResolution::getFindingKeyHash).containsExactly("ffff");
        // The other tenant's identical dismissal is untouched.
        assertThat(repository.findLive(OTHER_ACCOUNT)).hasSize(1);
    }

    @Test
    void retireIsIdempotentAndDoesNotResurrectAReason() {
        Instant now = Instant.now();
        resolution(ACCOUNT, "gggg", now);

        repository.retire(ACCOUNT, List.of("gggg"),
                SecurityFindingResolution.RETIRED_REOPENED_BY_PROVIDER, now);
        int second = repository.retire(ACCOUNT, List.of("gggg"),
                SecurityFindingResolution.RETIRED_STILL_PRESENT_IN_SCAN, now);

        // Already retired, so the second pass matches nothing and the original reason stands.
        assertThat(second).isZero();
        assertThat(repository.findHistory(ACCOUNT))
                .singleElement()
                .extracting(SecurityFindingResolution::getRetiredReason)
                .isEqualTo(SecurityFindingResolution.RETIRED_REOPENED_BY_PROVIDER);
    }

    @Test
    void historyKeepsRetiredRowsNewestFirst() {
        Instant now = Instant.now();
        resolution(ACCOUNT, "hhhh", now.minus(2, ChronoUnit.HOURS));
        SecurityFindingResolution newer = resolution(ACCOUNT, "iiii", now);
        newer.setRetiredAt(now);
        newer.setRetiredReason(SecurityFindingResolution.RETIRED_STILL_PRESENT_IN_SCAN);
        repository.save(newer);

        List<SecurityFindingResolution> history = repository.findHistory(ACCOUNT);

        // Retired rows are kept: "marked fixed, then came back" is the most useful record here.
        assertThat(history).extracting(SecurityFindingResolution::getFindingKeyHash)
                .containsExactly("iiii", "hhhh");
        assertThat(history.get(0).isLive()).isFalse();
        assertThat(history.get(1).isLive()).isTrue();
    }

    @Test
    void orphanSweepRemovesRowsWithNoAccount() {
        resolution(ACCOUNT, "jjjj", Instant.now());

        // No cloud_account rows exist in this slice, so every resolution is an orphan. Proves the
        // NOT IN subquery is valid JPQL, which is the point of exercising it here.
        int deleted = repository.deleteOrphanedByMissingAccount();

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.findLive(ACCOUNT)).isEmpty();
    }
}
