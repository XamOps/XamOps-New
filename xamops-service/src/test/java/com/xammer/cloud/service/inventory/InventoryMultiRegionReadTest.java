package com.xammer.cloud.service.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.AwsInventorySyncState;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.AwsCloudResourceRepository;
import com.xammer.cloud.repository.AwsInventorySyncStateRepository;
import com.xammer.cloud.service.AwsClientProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.services.resourceexplorer2.ResourceExplorer2Client;
import software.amazon.awssdk.services.resourceexplorer2.model.GetIndexRequest;
import software.amazon.awssdk.services.resourceexplorer2.model.GetIndexResponse;
import software.amazon.awssdk.services.resourceexplorer2.model.Index;
import software.amazon.awssdk.services.resourceexplorer2.model.IndexState;
import software.amazon.awssdk.services.resourceexplorer2.model.ListIndexesRequest;
import software.amazon.awssdk.services.resourceexplorer2.model.ListIndexesResponse;
import software.amazon.awssdk.services.resourceexplorer2.model.ListResourcesRequest;
import software.amazon.awssdk.services.resourceexplorer2.model.ListResourcesResponse;
import software.amazon.awssdk.services.resourceexplorer2.model.Resource;
import software.amazon.awssdk.services.resourceexplorer2.model.UnauthorizedException;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * How the reconciler reads an account whose Resource Explorer has no ACTIVE aggregator.
 *
 * <p>
 * Pins a failure seen on a newly connected account. Its only aggregator had just been
 * promoted in us-east-1 and was still replicating. ListIndexes already reported the type
 * AGGREGATOR, so the reconciler trusted it and read that one region: every bucket in us-east-1
 * appeared, every bucket in ap-south-1 was missing, and the plane was served as complete.
 *
 * <p>
 * Before that promotion the same account had LOCAL indexes only, and fared no better — the
 * reconciler read just the first region and reported success, so nothing ever looked at the
 * rest. Both paths now read every indexed region, and hold a region back from the sweep
 * whenever its read cannot be trusted as complete.
 */
class InventoryMultiRegionReadTest {

    private static final String US = "us-east-1";
    private static final String MUMBAI = "ap-south-1";

    private AwsClientProvider clients;
    private ResourceExplorer2Client usClient;
    private ResourceExplorer2Client mumbaiClient;
    private AwsInventoryReconciler reconciler;
    private CloudAccount account;

    @BeforeEach
    void setUp() {
        clients = mock(AwsClientProvider.class);
        usClient = mock(ResourceExplorer2Client.class);
        mumbaiClient = mock(ResourceExplorer2Client.class);

        account = new CloudAccount();
        account.setAwsAccountId("080310056620");

        when(clients.getResourceExplorerClient(any(CloudAccount.class), eq(US))).thenReturn(usClient);
        when(clients.getResourceExplorerClient(any(CloudAccount.class), eq(MUMBAI))).thenReturn(mumbaiClient);

        reconciler = new AwsInventoryReconciler(clients, mock(AwsCloudResourceRepository.class),
                mock(AwsInventorySyncStateRepository.class), new ObjectMapper(),
                mock(PlatformTransactionManager.class));
        ReflectionTestUtils.setField(reconciler, "defaultRegion", US);
        // Index creation and promotion write to the customer's account; not what is under test.
        ReflectionTestUtils.setField(reconciler, "autoCreateIndexes", false);

        // One bucket per region, the shape of the real account.
        when(usClient.listResources(any(ListResourcesRequest.class)))
                .thenReturn(resources(bucket("xam-testop", US)));
        when(mumbaiClient.listResources(any(ListResourcesRequest.class)))
                .thenReturn(resources(bucket("xamop-second-test", MUMBAI)));
        state(usClient, IndexState.ACTIVE);
        state(mumbaiClient, IndexState.ACTIVE);
    }

    @Test
    void readsEveryIndexedRegionWhenThereIsNoAggregator() {
        indexes(index(US, "LOCAL"), index(MUMBAI, "LOCAL"));

        Object info = resolve();
        assertEquals(AwsInventorySyncState.INDEX_LOCAL, ReflectionTestUtils.getField(info, "indexType"));
        assertEquals(List.of(US, MUMBAI), ReflectionTestUtils.getField(info, "queryRegions"),
                "every indexed region is read, default region first");

        Object fetched = fetch(info);
        assertEquals(Set.of("xam-testop", "xamop-second-test"), bucketNames(fetched),
                "the ap-south-1 bucket must be found — before this fix it never was");
        assertTrue(unsweepable(fetched).isEmpty());
    }

    @Test
    void anAggregatorStillReplicatingIsNotTrustedToCoverTheAccount() {
        // The exact state behind the missing ap-south-1 buckets.
        indexes(index(US, "AGGREGATOR"), index(MUMBAI, "LOCAL"));
        state(usClient, IndexState.UPDATING);

        Object info = resolve();

        assertEquals(AwsInventorySyncState.INDEX_LOCAL, ReflectionTestUtils.getField(info, "indexType"),
                "recording AGGREGATOR here is what let the plane be served as complete");
        assertEquals(List.of(US, MUMBAI), ReflectionTestUtils.getField(info, "queryRegions"));
        assertEquals(Set.of("xam-testop", "xamop-second-test"), bucketNames(fetch(info)));
    }

    @Test
    void anActiveAggregatorIsStillOneQuery() {
        indexes(index(US, "AGGREGATOR"), index(MUMBAI, "LOCAL"));
        when(usClient.listResources(any(ListResourcesRequest.class))).thenReturn(
                resources(bucket("xam-testop", US), bucket("xamop-second-test", MUMBAI)));

        Object info = resolve();

        assertEquals(AwsInventorySyncState.INDEX_AGGREGATOR, ReflectionTestUtils.getField(info, "indexType"));
        assertEquals(List.of(US), ReflectionTestUtils.getField(info, "queryRegions"),
                "the fast path is unchanged once the aggregator is ACTIVE");
        assertEquals(Set.of("xam-testop", "xamop-second-test"), bucketNames(fetch(info)));
    }

    @Test
    void aRegionThatCannotBeReadIsSkippedAndProtectedFromTheSweep() {
        // Most often a region with no default view, which ListResources requires.
        indexes(index(US, "LOCAL"), index(MUMBAI, "LOCAL"));
        when(mumbaiClient.listResources(any(ListResourcesRequest.class)))
                .thenThrow(UnauthorizedException.builder().message("Unauthorized: no default view").build());

        Object fetched = fetch(resolve());

        assertEquals(Set.of("xam-testop"), bucketNames(fetched), "one bad region must not hide the others");
        assertEquals(Set.of(MUMBAI), unsweepable(fetched),
                "an unread region says nothing about what was deleted there, so it is not swept");
    }

    @Test
    void aRegionStillIndexingIsReadButNotSwept() {
        // Reading only adds, so a partly built index still surfaces what it has. Deleting needs
        // a complete read, which an index that is not ACTIVE cannot promise.
        indexes(index(US, "LOCAL"), index(MUMBAI, "LOCAL"));
        state(mumbaiClient, IndexState.CREATING);

        Object fetched = fetch(resolve());

        assertEquals(Set.of("xam-testop", "xamop-second-test"), bucketNames(fetched));
        assertEquals(Set.of(MUMBAI), unsweepable(fetched));
    }

    @Test
    void aFailedAggregatorQueryFallsBackToEachRegion() {
        indexes(index(US, "AGGREGATOR"), index(MUMBAI, "LOCAL"));
        when(usClient.listResources(any(ListResourcesRequest.class)))
                .thenThrow(UnauthorizedException.builder().message("Unauthorized: no default view").build());

        Object fetched = fetch(resolve());

        assertEquals(Set.of("xamop-second-test"), bucketNames(fetched),
                "a broken aggregator no longer writes the whole account off");
        assertEquals(Set.of(US), unsweepable(fetched));
    }

    @Test
    void protectingMergesWithRegionsThatHadNoIndex() {
        indexes(index(US, "LOCAL"), index(MUMBAI, "LOCAL"));
        Object info = resolve();

        Object protectedInfo = ReflectionTestUtils.invokeMethod(info, "protecting", Set.of(MUMBAI));

        @SuppressWarnings("unchecked")
        Set<String> uncovered = (Set<String>) ReflectionTestUtils.getField(protectedInfo, "uncoveredRegions");
        assertEquals(Set.of(MUMBAI), uncovered);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Object resolve() {
        return ReflectionTestUtils.invokeMethod(reconciler, "resolveIndex", account);
    }

    private Object fetch(Object info) {
        return ReflectionTestUtils.invokeMethod(reconciler, "fetchInventory", account, info);
    }

    private static Set<String> bucketNames(Object fetched) {
        List<Resource> resources = ReflectionTestUtils.invokeMethod(fetched, "resources");
        return resources.stream()
                .map(r -> r.arn().substring(r.arn().lastIndexOf(':') + 1))
                .collect(Collectors.toSet());
    }

    private static Set<String> unsweepable(Object fetched) {
        return ReflectionTestUtils.invokeMethod(fetched, "unsweepableRegions");
    }

    private void indexes(Index... indexes) {
        when(usClient.listIndexes(any(ListIndexesRequest.class)))
                .thenReturn(ListIndexesResponse.builder().indexes(indexes).build());
    }

    private static void state(ResourceExplorer2Client client, IndexState state) {
        when(client.getIndex(any(GetIndexRequest.class)))
                .thenReturn(GetIndexResponse.builder().state(state).build());
    }

    private static Index index(String region, String type) {
        return Index.builder().region(region).type(type)
                .arn("arn:aws:resource-explorer-2:" + region + ":080310056620:index/" + type).build();
    }

    private static Resource bucket(String name, String region) {
        return Resource.builder().arn("arn:aws:s3:::" + name).region(region).resourceType("s3:bucket").build();
    }

    private static ListResourcesResponse resources(Resource... resources) {
        return ListResourcesResponse.builder().resources(resources).build();
    }
}
