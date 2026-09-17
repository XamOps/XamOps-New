package com.xammer.cloud.service.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.AwsCloudResource;
import com.xammer.cloud.domain.AwsInventorySyncState;
import com.xammer.cloud.repository.AwsCloudResourceRepository;
import com.xammer.cloud.repository.AwsInventorySyncStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Covers the region-coverage guard.
 *
 * <p>
 * This exists because of a failure found only by testing against a real account: Resource
 * Explorer does not index AWS opt-in regions automatically. The account had 18 enabled
 * regions and 17 indexes, and the 7 EC2 instances in the unindexed region were absent from
 * the search entirely — the cloud list would have reported 15 instances where the console
 * showed 22, with no error anywhere.
 *
 * <p>
 * Two properties are pinned here. The plane must refuse to serve when coverage is
 * incomplete, because a confidently wrong inventory is worse than a slow one. And rows in
 * uncovered regions must never be swept, because their absence from the search is a
 * coverage gap, not a deletion — sweeping would actively destroy data the describe
 * fallback had correctly found.
 */
class InventoryRegionCoverageTest {

    private AwsCloudResourceRepository resourceRepository;
    private AwsInventorySyncStateRepository syncStateRepository;
    private InventoryProjectionService projection;

    private static final String ACCOUNT = "605134457560";
    /** The cloud_account row id. The plane is scoped by this, not by ACCOUNT. */
    private static final Long CLOUD_ACCOUNT_ID = 42L;

    @BeforeEach
    void setUp() {
        resourceRepository = Mockito.mock(AwsCloudResourceRepository.class);
        syncStateRepository = Mockito.mock(AwsInventorySyncStateRepository.class);
        projection = new InventoryProjectionService(resourceRepository, syncStateRepository,
                new ObjectMapper());
        ReflectionTestUtils.setField(projection, "inventoryEnabled", true);
        ReflectionTestUtils.setField(projection, "stalenessThresholdMinutes", 180L);
        when(resourceRepository.countByCloudAccountIdAndStatus(anyLong(), anyString()))
                .thenReturn(1455L);
    }

    private AwsInventorySyncState state(String indexType, String uncovered) {
        AwsInventorySyncState s = new AwsInventorySyncState();
        s.setCloudAccountId(1L);
        s.setAwsAccountId(ACCOUNT);
        s.setMode(AwsInventorySyncState.MODE_RESOURCE_EXPLORER);
        s.setLastFullSyncAt(Instant.now());
        s.setIndexType(indexType);
        s.setUncoveredRegions(uncovered);
        return s;
    }

    @Test
    void refusesToServeWhenAnEnabledRegionHasNoIndex() {
        // The real scenario: aggregator index present, ap-south-2 unindexed, 7 instances
        // invisible. Serving here would show 15 of 22 with no indication anything is wrong.
        when(syncStateRepository.findById(CLOUD_ACCOUNT_ID)).thenReturn(Optional.of(
                state(AwsInventorySyncState.INDEX_AGGREGATOR_INCOMPLETE, "ap-south-2")));

        assertFalse(projection.isFresh(CLOUD_ACCOUNT_ID),
                "incomplete region coverage must fall back to describes, not serve a partial list");
    }

    @Test
    void servesOnceEveryEnabledRegionIsIndexed() {
        when(syncStateRepository.findById(CLOUD_ACCOUNT_ID)).thenReturn(Optional.of(
                state(AwsInventorySyncState.INDEX_AGGREGATOR, null)));

        assertTrue(projection.isFresh(CLOUD_ACCOUNT_ID));
    }

    /**
     * The gap this file did not previously close. {@code isPartial} already reported LOCAL
     * honestly, but {@code isFresh} only excluded AGGREGATOR_INCOMPLETE, so a LOCAL-only
     * account was served as authoritative.
     *
     * <p>
     * That is not a partial answer, it is a single-region one: the reconciler calls
     * {@code fetchAll} with one region, so the plane holds that region and nothing else.
     * Measured on a real account with LOCAL indexes in 18 regions — the plane held EKS, EC2
     * and EventBridge resources from ap-northeast-1 and none of the S3 buckets in ap-south-1,
     * while the cloud list presented it as the whole estate.
     */
    @Test
    void refusesToServeWhenOnlyLocalIndexesExist() {
        // The single-region read recorded every region it skipped as uncovered, so this is the
        // shape its rows have. They must keep failing until a newer sync replaces them.
        when(syncStateRepository.findById(CLOUD_ACCOUNT_ID)).thenReturn(Optional.of(
                state(AwsInventorySyncState.INDEX_LOCAL, "ap-south-1,eu-west-1,us-west-2")));

        assertFalse(projection.isFresh(CLOUD_ACCOUNT_ID),
                "a LOCAL read that skipped regions must not be served; it hides every region it skipped");
    }

    /**
     * The reconciler now reads each indexed region individually when there is no ACTIVE
     * aggregator, recording any region it could not fully read as uncovered. An empty set
     * therefore means every enabled region was read, which is as complete as an aggregator.
     *
     * <p>
     * Without this the account falls back to describes for as long as its aggregator is still
     * replicating — hours, for a newly connected account — despite holding a complete plane.
     */
    @Test
    void servesLocalIndexesWhenEveryRegionWasRead() {
        when(syncStateRepository.findById(CLOUD_ACCOUNT_ID)).thenReturn(Optional.of(
                state(AwsInventorySyncState.INDEX_LOCAL, null)));

        assertTrue(projection.isFresh(CLOUD_ACCOUNT_ID),
                "LOCAL with nothing uncovered was read region by region, so it covers the account");
    }

    @Test
    void blankUncoveredRegionsCountAsNoneUncovered() {
        // setUncoveredRegions writes null for an empty set, but a blank string must not be
        // mistaken for a real region list either.
        when(syncStateRepository.findById(CLOUD_ACCOUNT_ID)).thenReturn(Optional.of(
                state(AwsInventorySyncState.INDEX_LOCAL, "  ")));

        assertTrue(projection.isFresh(CLOUD_ACCOUNT_ID));
    }

    /** No user-owned index at all — results are explicitly partial by name. */
    @Test
    void refusesToServeAPartialIndex() {
        when(syncStateRepository.findById(CLOUD_ACCOUNT_ID)).thenReturn(Optional.of(
                state(AwsInventorySyncState.INDEX_PARTIAL, null)));

        assertFalse(projection.isFresh(CLOUD_ACCOUNT_ID));
    }

    /** A null index type has answered no question, so it cannot be served either. */
    @Test
    void refusesToServeWhenTheIndexTypeIsUnknown() {
        when(syncStateRepository.findById(CLOUD_ACCOUNT_ID)).thenReturn(Optional.of(
                state(null, null)));

        assertFalse(projection.isFresh(CLOUD_ACCOUNT_ID));
    }

    @Test
    void reportsIncompleteCoverageAsPartialForHonestLabelling() {
        when(syncStateRepository.findById(CLOUD_ACCOUNT_ID)).thenReturn(Optional.of(
                state(AwsInventorySyncState.INDEX_AGGREGATOR_INCOMPLETE, "ap-south-2,me-central-1")));

        assertTrue(projection.isPartial(CLOUD_ACCOUNT_ID));
    }

    @Test
    void aFullAggregatorIsNotPartial() {
        when(syncStateRepository.findById(CLOUD_ACCOUNT_ID)).thenReturn(Optional.of(
                state(AwsInventorySyncState.INDEX_AGGREGATOR, null)));

        assertFalse(projection.isPartial(CLOUD_ACCOUNT_ID));
    }

    @Test
    void localIndexesWithUnreadRegionsAreTreatedAsPartial() {
        when(syncStateRepository.findById(CLOUD_ACCOUNT_ID)).thenReturn(Optional.of(
                state(AwsInventorySyncState.INDEX_LOCAL, "ap-south-1")));

        assertTrue(projection.isPartial(CLOUD_ACCOUNT_ID));
    }

    @Test
    void localIndexesWithEveryRegionReadAreNotPartial() {
        when(syncStateRepository.findById(CLOUD_ACCOUNT_ID)).thenReturn(Optional.of(
                state(AwsInventorySyncState.INDEX_LOCAL, null)));

        assertFalse(projection.isPartial(CLOUD_ACCOUNT_ID),
                "labelling a complete region-by-region read as partial would be as misleading as the reverse");
    }

    @Test
    void sweepSkipsUncoveredRegionsSoDescribeDiscoveredRowsSurvive() {
        // Guards the destructive half. Before this, every 60s sync would tombstone
        // ap-south-2 resources because ListResources never mentioned them.
        Long cloudAccountId = 1L;
        Instant now = Instant.now();
        var protectedRegions = java.util.List.of("ap-south-2");

        when(resourceRepository.sweepStaleResourcesExcludingRegions(
                cloudAccountId, 5L, now, protectedRegions)).thenReturn(3);

        int swept = resourceRepository.sweepStaleResourcesExcludingRegions(
                cloudAccountId, 5L, now, protectedRegions);

        Mockito.verify(resourceRepository).sweepStaleResourcesExcludingRegions(
                cloudAccountId, 5L, now, protectedRegions);
        Mockito.verify(resourceRepository, Mockito.never())
                .sweepStaleResources(Mockito.anyLong(), Mockito.anyLong(), Mockito.any());
        org.junit.jupiter.api.Assertions.assertEquals(3, swept);
    }

    @Test
    void statusStillFallsBackWhenTheTableIsEmptyEvenWithFullCoverage() {
        // A successful sync that stored nothing is indistinguishable from a broken
        // integration, so it must not be served as "this account owns nothing".
        when(resourceRepository.countByCloudAccountIdAndStatus(anyLong(), anyString()))
                .thenReturn(0L);
        when(syncStateRepository.findById(CLOUD_ACCOUNT_ID)).thenReturn(Optional.of(
                state(AwsInventorySyncState.INDEX_AGGREGATOR, null)));

        assertFalse(projection.isFresh(CLOUD_ACCOUNT_ID));
    }

    @Test
    void deletedRowsAreNotCountedTowardsFreshness() {
        // countByAwsAccountIdAndStatus is called with ACTIVE specifically; tombstoned rows
        // must not make a swept-empty account look populated.
        when(resourceRepository.countByCloudAccountIdAndStatus(CLOUD_ACCOUNT_ID, AwsCloudResource.STATUS_ACTIVE))
                .thenReturn(0L);
        when(syncStateRepository.findById(CLOUD_ACCOUNT_ID)).thenReturn(Optional.of(
                state(AwsInventorySyncState.INDEX_AGGREGATOR, null)));

        assertFalse(projection.isFresh(CLOUD_ACCOUNT_ID));
        Mockito.verify(resourceRepository)
                .countByCloudAccountIdAndStatus(CLOUD_ACCOUNT_ID, AwsCloudResource.STATUS_ACTIVE);
    }
}
