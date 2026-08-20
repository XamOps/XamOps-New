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

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Pins the plane's scoping rule: everything is keyed on the {@code cloud_account} row id,
 * never on the 12-digit AWS account id.
 *
 * <p>
 * This is a regression test for a bug that only appeared once a customer disconnected and
 * reconnected an account. Reconnecting mints a <em>new</em> {@code cloud_account} row, so
 * one AWS account id then matched two rows. Two independent failures followed:
 *
 * <ul>
 * <li>The read path looked up sync state with an {@link Optional}-returning finder keyed on
 * the AWS account id, which threw {@code NonUniqueResultException}. Callers treat any
 * exception as "plane unavailable", so the cloud list silently reverted to the describe
 * path and served a stale cached snapshot — 20 S3 buckets when the account had 23. The
 * feature was off while every health signal said it was on, which is why this needs a test
 * rather than a fix alone.</li>
 * <li>The reconciler's upsert loaded existing rows by AWS account id while its sweep used
 * the connection id, so a sync attributed to the new connection updated the <em>old</em>
 * connection's rows in place. Measured live: 1640 resources fetched, 10 written.</li>
 * </ul>
 */
class InventoryAccountScopingTest {

    private AwsCloudResourceRepository resourceRepository;
    private AwsInventorySyncStateRepository syncStateRepository;
    private InventoryProjectionService service;

    /** One AWS account... */
    private static final String AWS_ACCOUNT = "605134457560";
    /** ...connected twice, so two cloud_account rows. */
    private static final Long OLD_CONNECTION = 100L;
    private static final Long NEW_CONNECTION = 102L;

    @BeforeEach
    void setUp() {
        resourceRepository = Mockito.mock(AwsCloudResourceRepository.class);
        syncStateRepository = Mockito.mock(AwsInventorySyncStateRepository.class);
        service = new InventoryProjectionService(resourceRepository, syncStateRepository,
                new ObjectMapper());
        ReflectionTestUtils.setField(service, "inventoryEnabled", true);
        ReflectionTestUtils.setField(service, "stalenessThresholdMinutes", 180L);
    }

    private AwsInventorySyncState stateFor(Long cloudAccountId) {
        AwsInventorySyncState s = new AwsInventorySyncState();
        s.setCloudAccountId(cloudAccountId);
        s.setAwsAccountId(AWS_ACCOUNT);
        s.setMode(AwsInventorySyncState.MODE_RESOURCE_EXPLORER);
        s.setLastFullSyncAt(Instant.now());
        s.setIndexType(AwsInventorySyncState.INDEX_AGGREGATOR);
        return s;
    }

    private AwsCloudResource row(Long cloudAccountId, String arn) {
        AwsCloudResource r = new AwsCloudResource();
        r.setCloudAccountId(cloudAccountId);
        r.setAwsAccountId(AWS_ACCOUNT);
        r.setArn(arn);
        r.setResourceId(arn.substring(arn.lastIndexOf(':') + 1));
        r.setDisplayType("S3 Bucket");
        r.setRegion("ap-south-1");
        r.setStatus(AwsCloudResource.STATUS_ACTIVE);
        return r;
    }

    /**
     * The headline regression. Two sync-state rows share one AWS account id; resolving by
     * primary key must still return a definite answer instead of throwing.
     */
    @Test
    void isFreshResolvesByConnectionEvenWhenTheAwsAccountIsConnectedTwice() {
        when(syncStateRepository.findById(OLD_CONNECTION))
                .thenReturn(Optional.of(stateFor(OLD_CONNECTION)));
        when(syncStateRepository.findById(NEW_CONNECTION))
                .thenReturn(Optional.of(stateFor(NEW_CONNECTION)));
        when(resourceRepository.countByCloudAccountIdAndStatus(anyLong(), anyString()))
                .thenReturn(1640L);

        assertTrue(service.isFresh(NEW_CONNECTION),
                "the current connection must be servable");
        assertTrue(service.isFresh(OLD_CONNECTION),
                "each connection is resolved independently, by primary key");
    }

    /**
     * Reads must not span connections. If they did, an account connected twice would list
     * every resource once per connection — the estate silently doubled.
     */
    @Test
    void readsAreScopedToOneConnectionAndDoNotSpanDuplicates() {
        when(syncStateRepository.findById(NEW_CONNECTION))
                .thenReturn(Optional.of(stateFor(NEW_CONNECTION)));
        when(resourceRepository.countByCloudAccountIdAndStatus(anyLong(), anyString()))
                .thenReturn(2L);

        // The same two ARNs exist under both connections, as they would right after a
        // reconnect and before the orphan purge runs.
        when(resourceRepository.findActiveByCloudAccountId(NEW_CONNECTION)).thenReturn(List.of(
                row(NEW_CONNECTION, "arn:aws:s3:::demobucket2172"),
                row(NEW_CONNECTION, "arn:aws:s3:::xamops-gcp-sa")));
        when(resourceRepository.findActiveByCloudAccountId(OLD_CONNECTION)).thenReturn(List.of(
                row(OLD_CONNECTION, "arn:aws:s3:::demobucket2172"),
                row(OLD_CONNECTION, "arn:aws:s3:::xamops-gcp-sa")));

        List<ResourceDto> dtos = service.flatList(NEW_CONNECTION);

        assertEquals(2, dtos.size(),
                "must return this connection's rows only, not the union of both");
    }

    /** A null connection id means "could not resolve" and must degrade, not throw. */
    @Test
    void unresolvableConnectionIsNotFreshRatherThanAnError() {
        assertFalse(service.isFresh(null));
        assertFalse(service.isPartial(null));
        assertTrue(service.syncState(null).isEmpty());
    }

    /**
     * Guards the fix structurally. The ambiguous finder was removed from the repository so
     * it cannot be reintroduced by reflex; if someone adds it back, this fails and points
     * at the reason.
     */
    @Test
    void syncStateRepositoryExposesNoLookupByAwsAccountId() {
        for (Method m : AwsInventorySyncStateRepository.class.getMethods()) {
            assertFalse(m.getName().equals("findByAwsAccountId"),
                    "findByAwsAccountId returns Optional but an AWS account id can match "
                            + "several cloud_account rows, so it throws NonUniqueResultException "
                            + "after a reconnect and silently disables the plane. Resolve the "
                            + "cloud_account row and use findById.");
        }
    }
}
