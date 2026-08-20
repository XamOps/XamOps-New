package com.xammer.cloud.service.inventory;

import com.xammer.cloud.domain.AwsCloudResource;
import com.xammer.cloud.domain.AwsInventorySyncState;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.AwsCloudResourceRepository;
import com.xammer.cloud.repository.AwsInventorySyncStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the event fast path.
 *
 * <p>
 * The invariants asserted here are the ones that keep an event stream from corrupting the
 * inventory: events must never sweep, must never advance the sync generation, and must be
 * idempotent under SQS at-least-once redelivery. Each of those, if broken, produces a
 * wrong inventory rather than a slow one — and a wrong inventory is worse than no event
 * pipeline at all.
 */
class InventoryEventIngestServiceTest {

    private AwsCloudResourceRepository resourceRepository;
    private AwsInventorySyncStateRepository syncStateRepository;
    private InventoryEventIngestService service;
    private CloudAccount account;

    private static final String ARN = "arn:aws:ec2:ap-south-1:123456789012:instance/i-0abc123";

    @BeforeEach
    void setUp() {
        resourceRepository = Mockito.mock(AwsCloudResourceRepository.class);
        syncStateRepository = Mockito.mock(AwsInventorySyncStateRepository.class);
        service = new InventoryEventIngestService(resourceRepository, syncStateRepository);

        account = new CloudAccount();
        account.setId(7L);
        account.setAwsAccountId("123456789012");

        AwsInventorySyncState state = new AwsInventorySyncState();
        state.setCloudAccountId(7L);
        state.setAwsAccountId("123456789012");
        state.setSyncGeneration(42L);
        when(syncStateRepository.findById(7L)).thenReturn(Optional.of(state));
    }

    private AwsCloudResource saved() {
        ArgumentCaptor<AwsCloudResource> captor = ArgumentCaptor.forClass(AwsCloudResource.class);
        verify(resourceRepository).save(captor.capture());
        return captor.getValue();
    }

    // ── Insert on create ────────────────────────────────────────────────────

    @Test
    void insertsANewResourceFromTheArnAlone() {
        when(resourceRepository.findByCloudAccountIdAndArn(7L, ARN)).thenReturn(Optional.empty());

        var outcome = service.apply(account, ARN, InventoryEventIngestService.ACTION_CREATED, "ap-south-1");

        assertEquals(InventoryEventIngestService.Outcome.CREATED, outcome);
        AwsCloudResource row = saved();
        // Everything the cloud list needs to render comes out of the ARN, so no
        // describe call is required to make the resource visible.
        assertEquals("i-0abc123", row.getResourceId());
        assertEquals("ec2", row.getService());
        assertEquals("ec2:instance", row.getNativeType());
        assertEquals("EC2 Instance", row.getDisplayType(), "must group with reconciler-inserted rows");
        assertEquals("ap-south-1", row.getRegion());
        assertEquals(AwsCloudResource.STATUS_ACTIVE, row.getStatus());
        assertEquals(AwsCloudResource.SOURCE_EVENT, row.getSource());
        assertNotNull(row.getFirstSeenAt());
    }

    @Test
    void inheritsTheCurrentGenerationSoTheNextSweepDoesNotWipeTheAccount() {
        // The subtle invariant. If an event wrote a *higher* generation, the next
        // reconcile would compare every other row against it and tombstone them all.
        when(resourceRepository.findByCloudAccountIdAndArn(7L, ARN)).thenReturn(Optional.empty());

        service.apply(account, ARN, InventoryEventIngestService.ACTION_CREATED, "ap-south-1");

        assertEquals(42L, saved().getSyncGeneration(),
                "event rows must join the current generation, not start a new one");
    }

    @Test
    void fallsBackToTheEventRegionForGlobalServiceArns() {
        // S3, IAM, Route 53 and CloudFront leave the region segment empty.
        String s3Arn = "arn:aws:s3:::my-bucket";
        when(resourceRepository.findByCloudAccountIdAndArn(7L, s3Arn)).thenReturn(Optional.empty());

        service.apply(account, s3Arn, InventoryEventIngestService.ACTION_CREATED, "us-east-1");

        AwsCloudResource row = saved();
        assertEquals("us-east-1", row.getRegion(), "region must come from the envelope when the ARN omits it");
        assertEquals("s3:bucket", row.getNativeType());
        assertEquals("S3 Bucket", row.getDisplayType());
    }

    // ── Tombstone on delete ─────────────────────────────────────────────────

    @Test
    void tombstonesRatherThanDeletingSoHistorySurvives() {
        AwsCloudResource existing = existingActiveRow();
        when(resourceRepository.findByCloudAccountIdAndArn(7L, ARN)).thenReturn(Optional.of(existing));

        var outcome = service.apply(account, ARN, InventoryEventIngestService.ACTION_DELETED, "ap-south-1");

        assertEquals(InventoryEventIngestService.Outcome.DELETED, outcome);
        AwsCloudResource row = saved();
        assertEquals(AwsCloudResource.STATUS_DELETED, row.getStatus());
        assertNotNull(row.getDeletedAt());
        verify(resourceRepository, never()).delete(any());
    }

    @Test
    void neverSweepsTheAccountOnADeleteEvent() {
        // Only the reconciler, which has observed the whole account, may sweep. A single
        // malformed delete event must not be able to empty a customer's inventory.
        AwsCloudResource existing = existingActiveRow();
        when(resourceRepository.findByCloudAccountIdAndArn(7L, ARN)).thenReturn(Optional.of(existing));

        service.apply(account, ARN, InventoryEventIngestService.ACTION_DELETED, "ap-south-1");

        verify(resourceRepository, never()).sweepStaleResources(anyLong(), anyLong(), any());
    }

    @Test
    void ignoresDeleteForAResourceWeNeverSaw() {
        // Created and destroyed between two reconciles. Nothing to do, and inserting a
        // tombstone for it would invent a resource that was never in the inventory.
        when(resourceRepository.findByCloudAccountIdAndArn(7L, ARN)).thenReturn(Optional.empty());

        var outcome = service.apply(account, ARN, InventoryEventIngestService.ACTION_DELETED, "ap-south-1");

        assertEquals(InventoryEventIngestService.Outcome.IGNORED, outcome);
        verify(resourceRepository, never()).save(any());
    }

    // ── Idempotency under redelivery ────────────────────────────────────────

    @Test
    void reprocessingACreateForAnExistingRowDoesNotDuplicateIt() {
        // SQS is at-least-once, and a failed batch delete causes redelivery. Applying the
        // same create twice must not produce two rows for one ARN.
        AwsCloudResource existing = existingActiveRow();
        when(resourceRepository.findByCloudAccountIdAndArn(7L, ARN)).thenReturn(Optional.of(existing));

        var outcome = service.apply(account, ARN, InventoryEventIngestService.ACTION_CREATED, "ap-south-1");

        assertEquals(InventoryEventIngestService.Outcome.UPDATED, outcome,
                "a repeat create is an update, not a second CREATED");
        assertEquals(existing.getFirstSeenAt(), saved().getFirstSeenAt(),
                "firstSeenAt must not be rewritten, or the 'new' badge never clears");
    }

    @Test
    void reprocessingADeleteIsIdempotent() {
        AwsCloudResource existing = existingActiveRow();
        existing.setStatus(AwsCloudResource.STATUS_DELETED);
        existing.setDeletedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        when(resourceRepository.findByCloudAccountIdAndArn(7L, ARN)).thenReturn(Optional.of(existing));

        var outcome = service.apply(account, ARN, InventoryEventIngestService.ACTION_DELETED, "ap-south-1");

        assertEquals(InventoryEventIngestService.Outcome.UPDATED, outcome);
    }

    @Test
    void resurrectsATombstonedArnAsCreatedBecauseArnsCanBeReused() {
        AwsCloudResource existing = existingActiveRow();
        existing.setStatus(AwsCloudResource.STATUS_DELETED);
        existing.setDeletedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        when(resourceRepository.findByCloudAccountIdAndArn(7L, ARN)).thenReturn(Optional.of(existing));

        var outcome = service.apply(account, ARN, InventoryEventIngestService.ACTION_CREATED, "ap-south-1");

        assertEquals(InventoryEventIngestService.Outcome.CREATED, outcome,
                "a returning resource should read as new, not silently reappear");
        AwsCloudResource row = saved();
        assertEquals(AwsCloudResource.STATUS_ACTIVE, row.getStatus());
        assertNull(row.getDeletedAt());
    }

    // ── Malformed input ─────────────────────────────────────────────────────

    @Test
    void ignoresInputThatIsNotAnArnWithoutTouchingTheDatabase() {
        // The forwarder scrapes ARNs out of CloudTrail payloads heuristically, so junk
        // reaching here is expected rather than exceptional.
        for (String bad : new String[] { null, "", "   ", "i-0abc123", "not-an-arn" }) {
            assertEquals(InventoryEventIngestService.Outcome.IGNORED,
                    service.apply(account, bad, InventoryEventIngestService.ACTION_CREATED, "ap-south-1"),
                    "expected IGNORED for: " + bad);
        }
        verify(resourceRepository, never()).save(any());
    }

    @Test
    void ignoresTruncatedArnsThatCannotYieldAService() {
        when(resourceRepository.findByCloudAccountIdAndArn(anyLong(), anyString()))
                .thenReturn(Optional.empty());

        assertEquals(InventoryEventIngestService.Outcome.IGNORED,
                service.apply(account, "arn:aws:ec2", InventoryEventIngestService.ACTION_CREATED, "ap-south-1"));
        verify(resourceRepository, never()).save(any());
    }

    @Test
    void skipsSuppressedTypesSoStackSetTargetsDoNotDoubleCount() {
        String arn = "arn:aws:cloudformation:ap-south-1:123456789012:stackset-target/abc";
        when(resourceRepository.findByCloudAccountIdAndArn(7L, arn)).thenReturn(Optional.empty());

        assertEquals(InventoryEventIngestService.Outcome.IGNORED,
                service.apply(account, arn, InventoryEventIngestService.ACTION_CREATED, "ap-south-1"));
        verify(resourceRepository, never()).save(any());
    }

    private AwsCloudResource existingActiveRow() {
        AwsCloudResource row = new AwsCloudResource();
        row.setId(1L);
        row.setCloudAccountId(7L);
        row.setAwsAccountId("123456789012");
        row.setArn(ARN);
        row.setResourceId("i-0abc123");
        row.setService("ec2");
        row.setNativeType("ec2:instance");
        row.setDisplayType("EC2 Instance");
        row.setRegion("ap-south-1");
        row.setStatus(AwsCloudResource.STATUS_ACTIVE);
        row.setFirstSeenAt(Instant.now().minus(3, ChronoUnit.DAYS));
        row.setLastSeenAt(Instant.now().minus(1, ChronoUnit.HOURS));
        row.setSyncGeneration(41L);
        return row;
    }
}
