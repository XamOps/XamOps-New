package com.xammer.cloud.service;

import com.xammer.cloud.domain.AwsCloudResource;
import com.xammer.cloud.repository.AwsCloudResourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The join invariant, which is the whole of this class's risk.
 *
 * <p>An identifier may be used to look costs up in the CUR only if a single claimant across every
 * status claims it. The tests that matter are the ones about the second inventory read: without it
 * a short id shared by two resources is invisible, because the repository query matches the ids it
 * was given and cannot see a resource that merely shares one — so the join would price an RDS
 * instance using an EKS cluster's rows, with full confidence and no marker.
 *
 * <p>Answerability is a separate question, judged on {@code ACTIVE} claimants alone. A sole-claimant
 * tombstone still prices, because a resource deleted mid-month still carries that month's charges.
 *
 * <p>The carve-out has its own section, and it is the half measured on a real account rather than
 * reasoned about: an AWS-generated id with several claimants is one machine indexed under several
 * service ARNs, not a collision, and refusing those ids blanked 73 of 80 active EC2 instances on
 * account {@code 605134457560}. The pair of tests that matter there are the instance that must
 * price and the customer-chosen {@code prod} that must not, because the two are indistinguishable
 * by ARN structure and separable only by the shape of the id.
 */
@ExtendWith(MockitoExtension.class)
class ResourceIdResolverTest {

    private static final Long ACCOUNT = 42L;

    @Mock
    private AwsCloudResourceRepository resourceRepo;

    private ResourceIdResolver resolver;
    private long nextId;

    @BeforeEach
    void setUp() {
        resolver = new ResourceIdResolver(resourceRepo);
        nextId = 1L;
    }

    // ── Candidate construction ──────────────────────────────────────────────

    @Test
    @DisplayName("A resolved id offers resource_id before arn, in that order")
    void candidatesAreShortIdThenArn() {
        AwsCloudResource rds = row("xammer-prod",
                "arn:aws:rds:ap-south-1:605134457560:db:xammer-prod", AwsCloudResource.STATUS_ACTIVE);
        answer(rds);

        ResourceIdResolver.CurIdentifiers found = resolver.curIdentifiers(ACCOUNT,
                List.of("arn:aws:rds:ap-south-1:605134457560:db:xammer-prod"));

        // resource_id first because that is the id the inventory-plane graph path emits, and that
        // is the preferred path.
        assertEquals(List.of("xammer-prod", "arn:aws:rds:ap-south-1:605134457560:db:xammer-prod"),
                found.byRequestedId().get("arn:aws:rds:ap-south-1:605134457560:db:xammer-prod"));
        assertTrue(found.ambiguous().isEmpty());
    }

    @Test
    @DisplayName("The same candidates come back whether the id asked about is the short id or the ARN")
    void resolutionIsPathIndependent() {
        AwsCloudResource lambda = row("image-processor",
                "arn:aws:lambda:ap-south-1:605134457560:function:image-processor",
                AwsCloudResource.STATUS_ACTIVE);

        answer(lambda);
        List<String> byShortId = resolver.curIdentifiers(ACCOUNT, List.of("image-processor"))
                .byRequestedId().get("image-processor");

        answer(lambda);
        List<String> byArn = resolver.curIdentifiers(ACCOUNT,
                        List.of("arn:aws:lambda:ap-south-1:605134457560:function:image-processor"))
                .byRequestedId()
                .get("arn:aws:lambda:ap-south-1:605134457560:function:image-processor");

        // This property is what makes the join work on both graph-serving paths rather than one:
        // nothing in the request says which vocabulary the caller holds.
        assertEquals(byShortId, byArn);
    }

    @Test
    @DisplayName("A malformed ARN whose tail is the whole ARN yields one candidate, not two")
    void equalIdentifierFormsCollapse() {
        // resourceIdFromArn returns the whole ARN when it finds no separator with content after
        // it, so resource_id == arn is reachable. Emitting it twice would put a duplicate in the
        // IN list and in nodeIdCandidates.
        answer(row("weird-arn", "weird-arn", AwsCloudResource.STATUS_ACTIVE));

        ResourceIdResolver.CurIdentifiers found =
                resolver.curIdentifiers(ACCOUNT, List.of("weird-arn"));

        assertEquals(List.of("weird-arn"), found.byRequestedId().get("weird-arn"));
    }

    @Test
    @DisplayName("An id the inventory has never seen is its own candidate and raises no apology")
    void unresolvedIdIsItsOwnCandidate() {
        answer();

        ResourceIdResolver.CurIdentifiers found =
                resolver.curIdentifiers(ACCOUNT, List.of("nat-0aa11bb22cc33dd44"));

        // Not a guess: it is the same string, matched by equality, which is correct for every
        // service whose CUR id is its bare id and finds nothing for the rest.
        assertEquals(List.of("nat-0aa11bb22cc33dd44"),
                found.byRequestedId().get("nat-0aa11bb22cc33dd44"));
        assertEquals(List.of("nat-0aa11bb22cc33dd44"),
                found.nodeIdCandidatesByRequestedId().get("nat-0aa11bb22cc33dd44"));
        assertTrue(found.ambiguous().isEmpty());
        assertNull(found.resolved().get("nat-0aa11bb22cc33dd44"));
    }

    @Test
    @DisplayName("Blank ids are dropped before anything is read")
    void blanksAreDropped() {
        ResourceIdResolver.CurIdentifiers found =
                resolver.curIdentifiers(ACCOUNT, List.of("", "   "));

        assertTrue(found.byRequestedId().isEmpty());
        verifyNoMoreInteractions(resourceRepo);
    }

    // ── The second read, and the collision it exists to find ────────────────

    @Test
    @DisplayName("A short id claimed by a second live resource never enters the predicate")
    void aSecondClaimantIsFoundByTheSecondRead() {
        AwsCloudResource rds = row("xammer-prod",
                "arn:aws:rds:ap-south-1:605134457560:db:xammer-prod", AwsCloudResource.STATUS_ACTIVE);
        AwsCloudResource eks = row("xammer-prod",
                "arn:aws:eks:ap-south-1:605134457560:cluster/xammer-prod",
                AwsCloudResource.STATUS_ACTIVE);
        // Pass 1 asks about the ARN and sees only the RDS row — which is the whole problem the
        // second pass solves. Pass 2 asks about "xammer-prod" and finds the cluster too.
        answerInOrder(List.of(rds), List.of(rds, eks));

        ResourceIdResolver.CurIdentifiers found = resolver.curIdentifiers(ACCOUNT,
                List.of("arn:aws:rds:ap-south-1:605134457560:db:xammer-prod"));

        assertEquals(List.of("arn:aws:rds:ap-south-1:605134457560:db:xammer-prod"),
                found.byRequestedId().get("arn:aws:rds:ap-south-1:605134457560:db:xammer-prod"));
        assertFalse(found.allCandidates().contains("xammer-prod"));
        // The ARN is single-claimant by construction, so the request still prices and nothing is
        // apologised for.
        assertTrue(found.ambiguous().isEmpty());

        ArgumentCaptor<Collection<String>> reads = captureReads(2);
        assertEquals(List.of("arn:aws:rds:ap-south-1:605134457560:db:xammer-prod"),
                new ArrayList<>(reads.getAllValues().get(0)));
        assertEquals(List.of("xammer-prod"), new ArrayList<>(reads.getAllValues().get(1)));
    }

    @Test
    @DisplayName("No second read when the first found nothing")
    void noSecondReadWithoutRows() {
        answer();

        resolver.curIdentifiers(ACCOUNT, List.of("i-0abc"));

        verify(resourceRepo, times(1)).findByCloudAccountIdAndAnyIdentifier(anyLong(), any());
    }

    @Test
    @DisplayName("Both reads chunk at 500 ids per statement")
    void bothReadsChunk() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 1_100; i++) {
            many.add("i-" + i);
        }
        answer();

        resolver.curIdentifiers(ACCOUNT, many);

        ArgumentCaptor<Collection<String>> reads = captureReads(3);
        assertEquals(500, reads.getAllValues().get(0).size());
        assertEquals(500, reads.getAllValues().get(1).size());
        assertEquals(100, reads.getAllValues().get(2).size());
    }

    @Test
    @DisplayName("A short id two live resources answer to prices neither and is marked")
    void aRequestedShortIdWithTwoLiveClaimantsIsUnanswerable() {
        AwsCloudResource rds = row("shared",
                "arn:aws:rds:ap-south-1:605134457560:db:shared", AwsCloudResource.STATUS_ACTIVE);
        AwsCloudResource eks = row("shared",
                "arn:aws:eks:ap-south-1:605134457560:cluster/shared",
                AwsCloudResource.STATUS_ACTIVE);
        answer(rds, eks);

        ResourceIdResolver.CurIdentifiers found = resolver.curIdentifiers(ACCOUNT, List.of("shared"));

        // Falling through to either row would emit that row's unique ARN and render a confident
        // figure that may be another team's database.
        assertEquals(List.of(), found.byRequestedId().get("shared"));
        assertTrue(found.ambiguous().contains("shared"));
        assertFalse(found.allCandidates().contains("arn:aws:rds:ap-south-1:605134457560:db:shared"));
        assertFalse(found.allCandidates()
                .contains("arn:aws:eks:ap-south-1:605134457560:cluster/shared"));
        // Still locatable: the resource is in the graph, so reporting it absent from the topology
        // would be a false statement with no retry path.
        assertEquals(List.of("shared"), found.nodeIdCandidatesByRequestedId().get("shared"));
        assertNull(found.resolved().get("shared"));
    }

    @Test
    @DisplayName("That same pair, requested by their ARNs, both price and neither is marked")
    void theSamePairPricesWhenAskedByArn() {
        AwsCloudResource rds = row("shared",
                "arn:aws:rds:ap-south-1:605134457560:db:shared", AwsCloudResource.STATUS_ACTIVE);
        AwsCloudResource eks = row("shared",
                "arn:aws:eks:ap-south-1:605134457560:cluster/shared",
                AwsCloudResource.STATUS_ACTIVE);
        answer(rds, eks);

        ResourceIdResolver.CurIdentifiers found = resolver.curIdentifiers(ACCOUNT, List.of(
                "arn:aws:rds:ap-south-1:605134457560:db:shared",
                "arn:aws:eks:ap-south-1:605134457560:cluster/shared"));

        // Only the colliding form is sacrificed. Each id keeps its ARN, which is unique, so both
        // are priced correctly — this is the common case and the one worth keeping cheap.
        assertEquals(List.of("arn:aws:rds:ap-south-1:605134457560:db:shared"),
                found.byRequestedId().get("arn:aws:rds:ap-south-1:605134457560:db:shared"));
        assertEquals(List.of("arn:aws:eks:ap-south-1:605134457560:cluster/shared"),
                found.byRequestedId().get("arn:aws:eks:ap-south-1:605134457560:cluster/shared"));
        assertFalse(found.allCandidates().contains("shared"));
        assertTrue(found.ambiguous().isEmpty());
    }

    // ── AWS-generated ids: several claimants, one machine ───────────────────

    @Test
    @DisplayName("An id AWS generated prices even when its EC2, SSM and STS ARNs all claim it")
    void awsGeneratedIdWithSeveralFacetsStillPrices() {
        AwsCloudResource instance = row("i-00e153b89ae72b8c3",
                "arn:aws:ec2:ap-south-1:605134457560:instance/i-00e153b89ae72b8c3",
                AwsCloudResource.STATUS_ACTIVE);
        instance.setDisplayType("EC2 Instance");
        instance.setLastSeenAt(Instant.parse("2026-10-14T06:00:00Z"));
        AwsCloudResource managed = row("i-00e153b89ae72b8c3",
                "arn:aws:ssm:ap-south-1:605134457560:managed-instance/i-00e153b89ae72b8c3",
                AwsCloudResource.STATUS_ACTIVE);
        managed.setLastSeenAt(Instant.parse("2026-10-14T05:00:00Z"));
        AwsCloudResource assumedRole = row("i-00e153b89ae72b8c3",
                "arn:aws:sts::605134457560:assumed-role/aws:ec2-infrastructure/i-00e153b89ae72b8c3",
                AwsCloudResource.STATUS_ACTIVE);
        assumedRole.setLastSeenAt(Instant.parse("2026-10-14T04:00:00Z"));
        answer(instance, managed, assumedRole);

        ResourceIdResolver.CurIdentifiers found =
                resolver.curIdentifiers(ACCOUNT, List.of("i-00e153b89ae72b8c3"));

        /*
         * The measured shape on account 605134457560: three ARNs, one physical instance. 183 short
         * ids there had two or more live claimants and all 183 resolved to a single ARN tail, so
         * counting ARN strings dropped 73 of the 80 active EC2 instance ids — the fleet — with no
         * real collision among them.
         */
        assertEquals(List.of("i-00e153b89ae72b8c3",
                        "arn:aws:ec2:ap-south-1:605134457560:instance/i-00e153b89ae72b8c3"),
                found.byRequestedId().get("i-00e153b89ae72b8c3"));
        assertTrue(found.ambiguous().isEmpty());
        // Described by the most recently seen facet. They are one machine, so this is a choice of
        // vantage point rather than the arbitrary pick between resources the invariant refuses.
        assertEquals("EC2 Instance", found.resolved().get("i-00e153b89ae72b8c3").resourceType());
    }

    @Test
    @DisplayName("The carve-out does not reach a customer-chosen name: 'prod' still prices neither")
    void aCustomerChosenNameIsStillAmbiguous() {
        AwsCloudResource rds = row("prod", "arn:aws:rds:ap-south-1:605134457560:db:prod",
                AwsCloudResource.STATUS_ACTIVE);
        AwsCloudResource eks = row("prod", "arn:aws:eks:ap-south-1:605134457560:cluster/prod",
                AwsCloudResource.STATUS_ACTIVE);
        answer(rds, eks);

        ResourceIdResolver.CurIdentifiers found = resolver.curIdentifiers(ACCOUNT, List.of("prod"));

        // These two ARNs also share a tail, which is why comparing tails was rejected as the
        // carve-out: it would read a database and a cluster as one resource. Nothing about the
        // string `prod` says AWS minted it, so it keeps the strict single-claimant test.
        assertEquals(List.of(), found.byRequestedId().get("prod"));
        assertTrue(found.ambiguous().contains("prod"));
        assertFalse(found.allCandidates().contains("arn:aws:rds:ap-south-1:605134457560:db:prod"));
        assertFalse(found.allCandidates()
                .contains("arn:aws:eks:ap-south-1:605134457560:cluster/prod"));
        assertNull(found.resolved().get("prod"));
    }

    @Test
    @DisplayName("An id AWS generated prices through a tombstone facet, under its short form")
    void awsGeneratedIdSurvivesATombstoneClaimant() {
        AwsCloudResource instance = row("i-0abc2002a2b3c4d5e",
                "arn:aws:ec2:ap-south-1:605134457560:instance/i-0abc2002a2b3c4d5e",
                AwsCloudResource.STATUS_ACTIVE);
        AwsCloudResource deregistered = row("i-0abc2002a2b3c4d5e",
                "arn:aws:ssm:ap-south-1:605134457560:managed-instance/i-0abc2002a2b3c4d5e",
                AwsCloudResource.STATUS_DELETED);
        deregistered.setLastSeenAt(Instant.parse("2026-09-01T00:00:00Z"));
        answer(instance, deregistered);

        ResourceIdResolver.CurIdentifiers found =
                resolver.curIdentifiers(ACCOUNT, List.of("i-0abc2002a2b3c4d5e"));

        // The short form is the one that matters: the CUR writes EC2 charges under the bare
        // `i-…`, never under the instance ARN, so keeping only the ARN here is a predicate that
        // technically prices and finds nothing.
        assertEquals(List.of("i-0abc2002a2b3c4d5e",
                        "arn:aws:ec2:ap-south-1:605134457560:instance/i-0abc2002a2b3c4d5e"),
                found.byRequestedId().get("i-0abc2002a2b3c4d5e"));
        assertTrue(found.ambiguous().isEmpty());
    }

    @Test
    @DisplayName("An id that only looks AWS-generated still needs a single claimant")
    void nearMissShapesGetNoCarveOut() {
        // Three digits where AWS writes at least eight, and letters that are not hex at all. Both
        // are strings a customer could have chosen, so neither buys the carve-out.
        for (String name : List.of("vol-123", "i-notyhex")) {
            answer(row(name, "arn:aws:rds:ap-south-1:605134457560:db:" + name,
                            AwsCloudResource.STATUS_ACTIVE),
                    row(name, "arn:aws:eks:ap-south-1:605134457560:cluster/" + name,
                            AwsCloudResource.STATUS_ACTIVE));

            ResourceIdResolver.CurIdentifiers found =
                    resolver.curIdentifiers(ACCOUNT, List.of(name));

            assertEquals(List.of(), found.byRequestedId().get(name), name);
            assertTrue(found.ambiguous().contains(name), name);
        }
    }

    // ── Status: irrelevant to uniqueness, decisive for answerability ────────

    @Test
    @DisplayName("A tombstone's short id collides as hard as a live one's")
    void aTombstoneStillPoisonsAShortId() {
        AwsCloudResource live = row("prod", "arn:aws:s3:::prod-live", AwsCloudResource.STATUS_ACTIVE);
        AwsCloudResource gone = row("prod", "arn:aws:eks:ap-south-1:605134457560:cluster/prod",
                AwsCloudResource.STATUS_DELETED);
        answer(live, gone);

        ResourceIdResolver.CurIdentifiers found =
                resolver.curIdentifiers(ACCOUNT, List.of("arn:aws:s3:::prod-live"));

        /*
         * Status-blind, and that is what stops a live resource absorbing a mid-month-deleted
         * resource's charges: the CUR has no concept of a deleted resource, so both resources'
         * rows sit in the same month under the same short id.
         */
        assertEquals(List.of("arn:aws:s3:::prod-live"),
                found.byRequestedId().get("arn:aws:s3:::prod-live"));
        assertFalse(found.allCandidates().contains("prod"));
        // Answerability is judged on live claimants only, and there is exactly one.
        assertTrue(found.ambiguous().isEmpty());
    }

    @Test
    @DisplayName("A sole-claimant tombstone still prices and raises no apology")
    void aSoleTombstoneStillPrices() {
        AwsCloudResource gone = row("vol-0aa1122334455667",
                "arn:aws:ec2:ap-south-1:605134457560:volume/vol-0aa1122334455667",
                AwsCloudResource.STATUS_DELETED);
        answer(gone);

        ResourceIdResolver.CurIdentifiers found =
                resolver.curIdentifiers(ACCOUNT, List.of("vol-0aa1122334455667"));

        // A volume detached and deleted three weeks ago is exactly what the connectivity tab is
        // asked to price. Refusing a tombstone would blank the most interesting rows in a flow.
        assertEquals(List.of("vol-0aa1122334455667",
                        "arn:aws:ec2:ap-south-1:605134457560:volume/vol-0aa1122334455667"),
                found.byRequestedId().get("vol-0aa1122334455667"));
        assertTrue(found.ambiguous().isEmpty());
        assertEquals("vol-0aa1122334455667",
                found.resolved().get("vol-0aa1122334455667").resourceId());
    }

    @Test
    @DisplayName("Two tombstones sharing an id resolve to the more recently seen, without an apology")
    void twoTombstonesTieBreakOnLastSeen() {
        AwsCloudResource older = row("prod", "arn:aws:s3:::prod-older",
                AwsCloudResource.STATUS_DELETED);
        older.setLastSeenAt(Instant.parse("2026-09-01T00:00:00Z"));
        AwsCloudResource newer = row("prod", "arn:aws:s3:::prod-newer",
                AwsCloudResource.STATUS_DELETED);
        newer.setLastSeenAt(Instant.parse("2026-10-01T00:00:00Z"));
        answer(older, newer);

        ResourceIdResolver.CurIdentifiers found = resolver.curIdentifiers(ACCOUNT, List.of("prod"));

        // Both resources are gone either way, so this is a bounded tie-break rather than a
        // refusal — and the shared short id is still dropped from the predicate.
        assertEquals(List.of("arn:aws:s3:::prod-newer"), found.byRequestedId().get("prod"));
        assertTrue(found.ambiguous().isEmpty());
    }

    @Test
    @DisplayName("A live row and the tombstone of the same resource are one resource, not two")
    void sameArnDuplicatesAreOneResource() {
        AwsCloudResource live = row("i-0abc1001", "arn:aws:ec2:ap-south-1:605134457560:instance/i-0abc1001",
                AwsCloudResource.STATUS_ACTIVE);
        live.setLastSeenAt(Instant.parse("2026-10-14T00:00:00Z"));
        AwsCloudResource stale = row("i-0abc1001", "arn:aws:ec2:ap-south-1:605134457560:instance/i-0abc1001",
                AwsCloudResource.STATUS_DELETED);
        stale.setLastSeenAt(Instant.parse("2026-08-01T00:00:00Z"));
        answer(live, stale);

        ResourceIdResolver.CurIdentifiers found =
                resolver.curIdentifiers(ACCOUNT, List.of("i-0abc1001"));

        // Claims are counted by ARN, so an earlier connection of the same resource cannot make
        // its own identifier ambiguous.
        assertEquals(List.of("i-0abc1001",
                        "arn:aws:ec2:ap-south-1:605134457560:instance/i-0abc1001"),
                found.byRequestedId().get("i-0abc1001"));
        assertTrue(found.ambiguous().isEmpty());
    }

    // ── Aliases ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Two requested ids resolving to one row are aliases and both own both candidates")
    void aliasesShareOwnership() {
        AwsCloudResource rds = row("xammer-prod",
                "arn:aws:rds:ap-south-1:605134457560:db:xammer-prod", AwsCloudResource.STATUS_ACTIVE);
        answer(rds);

        ResourceIdResolver.CurIdentifiers found = resolver.curIdentifiers(ACCOUNT,
                List.of("xammer-prod", "arn:aws:rds:ap-south-1:605134457560:db:xammer-prod"));

        // owners() is not injective and the design does not claim it is: a caller asking by both
        // forms in one request is two names for one resource, and a last-write-wins map would
        // silently blank one of them.
        assertEquals(List.of("xammer-prod", "arn:aws:rds:ap-south-1:605134457560:db:xammer-prod"),
                found.owners().get("xammer-prod"));
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    /** One inventory row, with a distinct primary key and a default sighting. */
    private AwsCloudResource row(String resourceId, String arn, String status) {
        AwsCloudResource row = new AwsCloudResource();
        row.setId(nextId++);
        row.setCloudAccountId(ACCOUNT);
        row.setResourceId(resourceId);
        row.setArn(arn);
        row.setStatus(status);
        row.setDisplayType("RDS Instance");
        row.setName(resourceId);
        row.setRegion("ap-south-1");
        row.setLastSeenAt(Instant.parse("2026-10-14T00:00:00Z"));
        return row;
    }

    /** Both reads answer with the same rows — the ordinary case. */
    private void answer(AwsCloudResource... rows) {
        when(resourceRepo.findByCloudAccountIdAndAnyIdentifier(eq(ACCOUNT), any()))
                .thenReturn(List.of(rows));
    }

    /** The reads answer differently, which is how the second pass is made observable. */
    private void answerInOrder(List<AwsCloudResource> first, List<AwsCloudResource> second) {
        when(resourceRepo.findByCloudAccountIdAndAnyIdentifier(eq(ACCOUNT), any()))
                .thenReturn(first, second);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Collection<String>> captureReads(int expected) {
        ArgumentCaptor<Collection<String>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(resourceRepo, times(expected))
                .findByCloudAccountIdAndAnyIdentifier(eq(ACCOUNT), ids.capture());
        return ids;
    }
}
