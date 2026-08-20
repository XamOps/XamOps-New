package com.xammer.cloud.service.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.AwsCloudResource;
import com.xammer.cloud.domain.AwsInventorySyncState;
import com.xammer.cloud.dto.ResourceDto;
import com.xammer.cloud.repository.AwsCloudResourceRepository;
import com.xammer.cloud.repository.AwsInventorySyncStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Covers {@code isFresh}, the gate that decides whether a page trusts the inventory
 * plane or falls back to the ~1000-call describe path.
 *
 * <p>
 * Every false case here is a scenario where trusting the plane would show a customer an
 * inventory that is wrong rather than merely slow — an empty cloud list, or a partial one
 * presented as complete. Getting this gate wrong is worse than not building the plane at
 * all, which is why the negative cases outnumber the positive one.
 */
class InventoryProjectionServiceTest {

    private AwsCloudResourceRepository resourceRepository;
    private AwsInventorySyncStateRepository syncStateRepository;
    private InventoryProjectionService service;

    private static final String ACCOUNT = "123456789012";
    /** The cloud_account row id. The plane is scoped by this, not by ACCOUNT. */
    private static final Long CLOUD_ACCOUNT_ID = 42L;

    @BeforeEach
    void setUp() {
        resourceRepository = Mockito.mock(AwsCloudResourceRepository.class);
        syncStateRepository = Mockito.mock(AwsInventorySyncStateRepository.class);
        service = new InventoryProjectionService(resourceRepository, syncStateRepository,
                new ObjectMapper());
        ReflectionTestUtils.setField(service, "inventoryEnabled", true);
        ReflectionTestUtils.setField(service, "stalenessThresholdMinutes", 180L);
    }

    private AwsInventorySyncState state(String mode, Instant lastSync) {
        AwsInventorySyncState s = new AwsInventorySyncState();
        s.setCloudAccountId(1L);
        s.setAwsAccountId(ACCOUNT);
        s.setMode(mode);
        s.setLastFullSyncAt(lastSync);
        s.setIndexType(AwsInventorySyncState.INDEX_AGGREGATOR);
        return s;
    }

    private void withRows(long count) {
        when(resourceRepository.countByCloudAccountIdAndStatus(anyLong(), anyString()))
                .thenReturn(count);
    }

    // ── The one case where the plane is trusted ─────────────────────────────

    @Test
    void isFreshWhenSyncIsRecentSuccessfulAndHasRows() {
        when(syncStateRepository.findById(CLOUD_ACCOUNT_ID)).thenReturn(
                Optional.of(state(AwsInventorySyncState.MODE_RESOURCE_EXPLORER, Instant.now())));
        withRows(4200);

        assertTrue(service.isFresh(CLOUD_ACCOUNT_ID));
    }

    // ── Cases that must fall back ───────────────────────────────────────────

    @Test
    void notFreshWhenFeatureFlagIsOff() {
        // The flag must win over everything else, so the rollout is genuinely reversible
        // without a deploy.
        ReflectionTestUtils.setField(service, "inventoryEnabled", false);

        assertFalse(service.isFresh(CLOUD_ACCOUNT_ID));
        Mockito.verifyNoInteractions(syncStateRepository);
    }

    @Test
    void notFreshWhenAccountHasNeverSynced() {
        when(syncStateRepository.findById(CLOUD_ACCOUNT_ID)).thenReturn(Optional.empty());

        assertFalse(service.isFresh(CLOUD_ACCOUNT_ID));
    }

    @Test
    void notFreshWhenAccountIsOnDescribeFallback() {
        // Resource Explorer blocked by an SCP, or the index is still building.
        when(syncStateRepository.findById(CLOUD_ACCOUNT_ID)).thenReturn(
                Optional.of(state(AwsInventorySyncState.MODE_DESCRIBE_FALLBACK, Instant.now())));

        assertFalse(service.isFresh(CLOUD_ACCOUNT_ID));
    }

    @Test
    void notFreshWhenLastSyncIsOlderThanTheStalenessThreshold() {
        when(syncStateRepository.findById(CLOUD_ACCOUNT_ID)).thenReturn(
                Optional.of(state(AwsInventorySyncState.MODE_RESOURCE_EXPLORER,
                        Instant.now().minus(4, ChronoUnit.HOURS))));

        assertFalse(service.isFresh(CLOUD_ACCOUNT_ID), "4h old with a 180min threshold must fall back");
    }

    @Test
    void notFreshWhenSyncSucceededButStoredZeroRows() {
        // The subtle one. A sync can report success and still leave the table empty, and
        // serving that would tell the customer they own nothing — indistinguishable from
        // a broken integration. Falling back is the only honest answer.
        when(syncStateRepository.findById(CLOUD_ACCOUNT_ID)).thenReturn(
                Optional.of(state(AwsInventorySyncState.MODE_RESOURCE_EXPLORER, Instant.now())));
        withRows(0);

        assertFalse(service.isFresh(CLOUD_ACCOUNT_ID));
    }

    @Test
    void notFreshWhenModeIsSuccessfulButTimestampIsMissing() {
        when(syncStateRepository.findById(CLOUD_ACCOUNT_ID)).thenReturn(
                Optional.of(state(AwsInventorySyncState.MODE_RESOURCE_EXPLORER, null)));

        assertFalse(service.isFresh(CLOUD_ACCOUNT_ID));
    }

    // ── Partial-result labelling ────────────────────────────────────────────

    @Test
    void reportsPartialForIndexTypesThatCannotSeeTheWholeAccount() {
        AwsInventorySyncState partial = state(AwsInventorySyncState.MODE_RESOURCE_EXPLORER, Instant.now());
        partial.setIndexType(AwsInventorySyncState.INDEX_PARTIAL);
        when(syncStateRepository.findById(CLOUD_ACCOUNT_ID)).thenReturn(Optional.of(partial));

        assertTrue(service.isPartial(CLOUD_ACCOUNT_ID), "no service-linked role means missing history");

        partial.setIndexType(AwsInventorySyncState.INDEX_LOCAL);
        assertTrue(service.isPartial(CLOUD_ACCOUNT_ID), "a local index covers only one region");

        partial.setIndexType(AwsInventorySyncState.INDEX_AGGREGATOR);
        assertFalse(service.isPartial(CLOUD_ACCOUNT_ID), "an aggregator index spans every region");
    }

    // ── DTO shape must not drift from CloudListService ───────────────────────

    @Test
    void projectsRowsIntoTheResourceDtoShapeConsumersAlreadyExpect() {
        AwsCloudResource row = new AwsCloudResource();
        row.setResourceId("i-0abc123");
        row.setName("web-server-1");
        row.setDisplayType("EC2 Instance");
        row.setRegion("ap-south-1");
        row.setState("running");
        row.setArn("arn:aws:ec2:ap-south-1:123456789012:instance/i-0abc123");
        row.setTags("{\"Name\":\"web-server-1\",\"env\":\"prod\"}");
        row.setDetails("{\"Instance Type\":\"t3.medium\"}");
        when(resourceRepository.findActiveByCloudAccountId(CLOUD_ACCOUNT_ID)).thenReturn(List.of(row));

        List<ResourceDto> dtos = service.flatList(CLOUD_ACCOUNT_ID);

        assertEquals(1, dtos.size());
        ResourceDto dto = dtos.get(0);
        assertEquals("i-0abc123", dto.getId());
        assertEquals("web-server-1", dto.getName());
        assertEquals("EC2 Instance", dto.getType(), "must match what consumers filter on");
        assertEquals("ap-south-1", dto.getRegion());
        assertEquals("running", dto.getState());
        // Details carry type-specific attributes, tags merged in, and the ARN so the
        // drill-down can resolve the resource without rebuilding it from id + type.
        assertEquals("t3.medium", dto.getDetails().get("Instance Type"));
        assertEquals("prod", dto.getDetails().get("env"));
        assertEquals("arn:aws:ec2:ap-south-1:123456789012:instance/i-0abc123",
                dto.getDetails().get("ARN"));
    }

    @Test
    void groupsByDisplayTypeSortedTheSameWayTheFrontendPaginates() {
        when(resourceRepository.findActiveByCloudAccountId(CLOUD_ACCOUNT_ID)).thenReturn(List.of(
                rowOfType("VPC", "vpc-1"),
                rowOfType("EC2 Instance", "i-1"),
                rowOfType("EC2 Instance", "i-2")));

        var groups = service.groupedList(CLOUD_ACCOUNT_ID);

        assertEquals(2, groups.size());
        // Alphabetical by service type, matching CloudListService's comparator — the
        // frontend pages five services at a time and relies on a stable order.
        assertEquals("EC2 Instance", groups.get(0).getServiceType());
        assertEquals(2, groups.get(0).getResources().size());
        assertEquals("VPC", groups.get(1).getServiceType());
    }

    @Test
    void toleratesMalformedJsonColumnsRatherThanFailingTheWholePage() {
        AwsCloudResource row = rowOfType("EC2 Instance", "i-1");
        row.setTags("{not valid json");
        row.setDetails(null);
        when(resourceRepository.findActiveByCloudAccountId(CLOUD_ACCOUNT_ID)).thenReturn(List.of(row));

        List<ResourceDto> dtos = service.flatList(CLOUD_ACCOUNT_ID);

        assertEquals(1, dtos.size(), "one bad row must not empty the cloud list");
        assertEquals("i-1", dtos.get(0).getId());
    }

    private AwsCloudResource rowOfType(String displayType, String id) {
        AwsCloudResource row = new AwsCloudResource();
        row.setResourceId(id);
        row.setName(id);
        row.setDisplayType(displayType);
        row.setRegion("ap-south-1");
        row.setArn("arn:aws:ec2:ap-south-1:123456789012:instance/" + id);
        return row;
    }
}
