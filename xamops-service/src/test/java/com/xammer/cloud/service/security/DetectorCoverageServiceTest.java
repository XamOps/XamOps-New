package com.xammer.cloud.service.security;

import com.xammer.cloud.domain.AwsInventorySyncState;
import com.xammer.cloud.repository.AwsInventorySyncStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Covers the distinction this service exists to make.
 *
 * <p>
 * An account on an out-of-date stack produces no exposure findings. So does a genuinely well-run
 * account. Both render an empty Security Center page, and without a capability signal the product
 * shows an unmonitored customer a clean bill of health — the most damaging thing a security feature
 * can do. These tests pin the three states apart and, in particular, keep "never connected" from
 * being reported as "update your stack", which would tell the customer to do the wrong thing.
 */
class DetectorCoverageServiceTest {

    private AwsInventorySyncStateRepository syncStateRepository;
    private DetectorCoverageService service;

    private static final Long CLOUD_ACCOUNT_ID = 42L;

    @BeforeEach
    void setUp() {
        syncStateRepository = Mockito.mock(AwsInventorySyncStateRepository.class);
        service = new DetectorCoverageService(syncStateRepository);
    }

    private void state(Instant lastEventAt, Integer schemaVersion) {
        AwsInventorySyncState s = new AwsInventorySyncState();
        s.setCloudAccountId(CLOUD_ACCOUNT_ID);
        s.setAwsAccountId("123456789012");
        s.setLastEventAt(lastEventAt);
        s.setEventSchemaVersion(schemaVersion);
        when(syncStateRepository.findById(anyLong())).thenReturn(Optional.of(s));
    }

    @Test
    void noEventsEverReceivedIsNotConnected() {
        state(null, null);

        Map<String, Object> coverage = service.coverageFor(CLOUD_ACCOUNT_ID);

        assertEquals("NOT_CONNECTED", coverage.get("status"));
        assertEquals(false, coverage.get("eventsReceived"));
        assertTrue(String.valueOf(coverage.get("message")).contains("scheduled scans only"));
        assertFalse(String.valueOf(coverage.get("message")).contains("Update your"),
                "telling someone who never installed the stack to update it is the wrong advice");
    }

    @Test
    void noSyncStateAtAllIsAlsoNotConnected() {
        when(syncStateRepository.findById(anyLong())).thenReturn(Optional.empty());

        assertEquals("NOT_CONNECTED", service.coverageFor(CLOUD_ACCOUNT_ID).get("status"));
        assertEquals("NOT_CONNECTED", service.coverageFor(null).get("status"));
    }

    /** Events arriving without detail: the one case that warrants the update nudge. */
    @Test
    void version1ForwarderIsReportedAsOutdatedWithACount() {
        state(Instant.now(), 1);

        Map<String, Object> coverage = service.coverageFor(CLOUD_ACCOUNT_ID);

        assertEquals("STACK_OUTDATED", coverage.get("status"));
        assertEquals(ExposureDetector.handledEventNames().size(),
                coverage.get("missingDetectorCount"));
        String message = String.valueOf(coverage.get("message"));
        assertTrue(message.contains("missing"), message);
        // Names the consequence, not the version number: "you are on schema 1" tells a customer
        // nothing about their risk.
        assertTrue(message.contains("made public"), message);
    }

    /**
     * Events arriving with no recorded version are treated as outdated, not as current. Absence of
     * evidence for a capability is not evidence of it.
     */
    @Test
    void eventsWithNoRecordedVersionAreTreatedAsOutdated() {
        state(Instant.now(), null);

        assertEquals("STACK_OUTDATED", service.coverageFor(CLOUD_ACCOUNT_ID).get("status"));
    }

    @Test
    void version2ForwarderIsCurrentAndNothingIsMissing() {
        state(Instant.now(), DetectorCoverageService.SCHEMA_WITH_EXPOSURE_DETAIL);

        Map<String, Object> coverage = service.coverageFor(CLOUD_ACCOUNT_ID);

        assertEquals("CURRENT", coverage.get("status"));
        assertEquals(0, coverage.get("missingDetectorCount"));
        assertTrue(service.isCurrent(CLOUD_ACCOUNT_ID));
        assertEquals(ExposureDetector.handledEventNames().size()
                + SecurityFindingDetector.detectedEventNames().size(),
                coverage.get("activeDetectorCount"));
    }

    /** A future forwarder must not be reported as outdated. */
    @Test
    void newerVersionThanExpectedIsStillCurrent() {
        state(Instant.now(), 99);

        assertEquals("CURRENT", service.coverageFor(CLOUD_ACCOUNT_ID).get("status"));
    }

    /**
     * Only the name-only detectors count as active on an old stack. Reporting the full catalogue
     * would overstate what is running, which is the same lie the whole class exists to prevent.
     */
    @Test
    void outdatedStackCountsOnlyTheDetectorsThatCanActuallyRun() {
        state(Instant.now(), 1);

        assertEquals(SecurityFindingDetector.detectedEventNames().size(),
                service.coverageFor(CLOUD_ACCOUNT_ID).get("activeDetectorCount"));
    }

    @Test
    void catalogueListsBothDetectorFamilies() {
        Map<String, Object> catalogue = service.catalogue();

        assertEquals(SecurityFindingDetector.detectedEventNames(),
                catalogue.get("nameOnlyDetectors"));
        assertEquals(ExposureDetector.handledEventNames(), catalogue.get("detailAwareDetectors"));
    }
}
