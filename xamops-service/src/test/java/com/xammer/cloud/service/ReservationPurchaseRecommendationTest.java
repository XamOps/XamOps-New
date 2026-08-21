package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.CostExplorerException;
import software.amazon.awssdk.services.costexplorer.model.DataUnavailableException;
import software.amazon.awssdk.services.costexplorer.model.GetReservationPurchaseRecommendationRequest;
import software.amazon.awssdk.services.costexplorer.model.GetReservationPurchaseRecommendationResponse;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the cache behaviour behind "no recommendations for any payment option except No
 * Upfront".
 *
 * <p>Each filter combination is a separate cache key. An empty result produced by a failed
 * Cost Explorer call used to be written to Redis for 24 hours and to a DB snapshot with no
 * TTL at all, and the UI renders empty as "Fully Optimized" — so one throttled call pinned a
 * false all-clear on that combination indefinitely. Querying six services per request rather
 * than one made throttling far likelier, which is how only some combinations went blank.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReservationPurchaseRecommendationTest {

    @Mock CloudAccountRepository cloudAccountRepository;
    @Mock AwsClientProvider awsClientProvider;
    @Mock CloudListService cloudListService;
    @Mock RedisCacheService redisCache;
    @Mock CostMockService costMockService;
    @Mock CachedDataRepository cachedDataRepository;
    @Mock CostExplorerClient costExplorerClient;

    private ReservationService service;
    private CloudAccount account;
    private List<Long> recordedWaits;

    @BeforeEach
    void setUp() {
        service = new ReservationService(
                cloudAccountRepository,
                awsClientProvider,
                cloudListService,
                List.of("nano", "micro", "small"),
                redisCache,
                costMockService,
                Runnable::run,
                cachedDataRepository,
                new ObjectMapper());
        // Exercise the retry logic without the real exponential waits.
        List<Long> waits = new java.util.ArrayList<>();
        service.setThrottleBackoffForTesting(waits::add);
        recordedWaits = waits;

        account = new CloudAccount();
        account.setId(1L);
        account.setAwsAccountId("123456789012");

        when(awsClientProvider.getCostExplorerClient(any())).thenReturn(costExplorerClient);
        when(redisCache.get(anyString(), any(TypeReference.class))).thenReturn(Optional.empty());
        when(cachedDataRepository.findById(anyString())).thenReturn(Optional.empty());
    }

    private ReservationService.PurchaseRecommendationSet fetch(String paymentOption) {
        return service.getPurchaseRecommendationSet(
                account, "ONE_YEAR", paymentOption, "THIRTY_DAYS", "STANDARD", false).join();
    }

    /** A 429-equivalent that the SDK reports as a throttling exception. */
    private static CostExplorerException throttled() {
        return (CostExplorerException) CostExplorerException.builder()
                .message("Rate exceeded")
                .statusCode(429)
                .awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                        .errorCode("ThrottlingException")
                        .errorMessage("Rate exceeded")
                        .build())
                .build();
    }

    @Test
    @DisplayName("a throttled lookup is reported incomplete rather than as an empty all-clear")
    void throttledLookupIsIncomplete() {
        when(costExplorerClient.getReservationPurchaseRecommendation(
                any(GetReservationPurchaseRecommendationRequest.class)))
                .thenThrow(throttled());

        ReservationService.PurchaseRecommendationSet result = fetch("PARTIAL_UPFRONT");

        assertThat(result.recommendations()).isEmpty();
        assertThat(result.complete()).isFalse();
        assertThat(result.failedServices()).isNotEmpty();
    }

    @Test
    @DisplayName("an incomplete result is never written to the DB snapshot")
    void incompleteResultIsNotPersisted() {
        when(costExplorerClient.getReservationPurchaseRecommendation(
                any(GetReservationPurchaseRecommendationRequest.class)))
                .thenThrow(throttled());

        fetch("ALL_UPFRONT");

        // The DB snapshot has no TTL, so persisting an empty failed result made the false
        // all-clear permanent for that filter combination.
        verify(cachedDataRepository, never()).save(any(CachedData.class));
    }

    @Test
    @DisplayName("an incomplete result gets only a short Redis TTL, not 24 hours")
    void incompleteResultGetsShortTtl() {
        when(costExplorerClient.getReservationPurchaseRecommendation(
                any(GetReservationPurchaseRecommendationRequest.class)))
                .thenThrow(throttled());

        fetch("PARTIAL_UPFRONT");

        verify(redisCache, never()).put(anyString(), any(), eq(60 * 24));
        verify(redisCache).put(anyString(), any(), eq(5));
    }

    @Test
    @DisplayName("no usage history is a real empty answer, so the result stays complete")
    void noUsageDataIsCompleteAndEmpty() {
        when(costExplorerClient.getReservationPurchaseRecommendation(
                any(GetReservationPurchaseRecommendationRequest.class)))
                .thenThrow(DataUnavailableException.builder().message("No data").build());

        ReservationService.PurchaseRecommendationSet result = fetch("NO_UPFRONT");

        assertThat(result.recommendations()).isEmpty();
        assertThat(result.complete()).isTrue();
        assertThat(result.failedServices()).isEmpty();
        // A genuine "nothing to reserve" is safe to cache for the full period.
        verify(redisCache).put(anyString(), any(), eq(60 * 24));
    }

    @Test
    @DisplayName("throttling is retried before the service is given up on")
    void throttlingIsRetried() {
        when(costExplorerClient.getReservationPurchaseRecommendation(
                any(GetReservationPurchaseRecommendationRequest.class)))
                .thenThrow(throttled())
                .thenReturn(GetReservationPurchaseRecommendationResponse.builder().build());

        ReservationService.PurchaseRecommendationSet result = fetch("PARTIAL_UPFRONT");

        // The single throttle hits the first service, which then succeeds on retry; the
        // remaining six succeed first time. Eight calls for seven services proves the retry
        // happened, and the set is still complete — previously that first service would have
        // silently dropped out.
        assertThat(result.complete()).isTrue();
        verify(costExplorerClient, times(8)).getReservationPurchaseRecommendation(
                any(GetReservationPurchaseRecommendationRequest.class));
        assertThat(recordedWaits).containsExactly(500L);
    }

    @Test
    @DisplayName("backoff grows exponentially and gives up after the attempt limit")
    void backoffIsExponentialAndBounded() {
        when(costExplorerClient.getReservationPurchaseRecommendation(
                any(GetReservationPurchaseRecommendationRequest.class)))
                .thenThrow(throttled());

        fetch("PARTIAL_UPFRONT");

        // Four attempts per service means three waits: 500ms, 1s, 2s. Seven services.
        // A throttle is not a name rejection, so the alias probe does not advance — each
        // service is attempted under its first candidate only.
        assertThat(recordedWaits).hasSize(21);
        assertThat(recordedWaits.subList(0, 3)).containsExactly(500L, 1000L, 2000L);
        verify(costExplorerClient, times(28)).getReservationPurchaseRecommendation(
                any(GetReservationPurchaseRecommendationRequest.class));
    }

    @Test
    @DisplayName("an empty DB snapshot is ignored so a stale all-clear can self-heal")
    void emptyDbSnapshotIsIgnored() {
        CachedData emptySnapshot = new CachedData();
        emptySnapshot.setCacheKey("RESERVATION_PURCHASE_RECS::123456789012::ONE_YEAR::ALL_UPFRONT::THIRTY_DAYS::STANDARD");
        emptySnapshot.setJsonData("[]");
        when(cachedDataRepository.findById(anyString())).thenReturn(Optional.of(emptySnapshot));
        when(costExplorerClient.getReservationPurchaseRecommendation(
                any(GetReservationPurchaseRecommendationRequest.class)))
                .thenReturn(GetReservationPurchaseRecommendationResponse.builder().build());

        fetch("ALL_UPFRONT");

        // Falls through to a live query rather than serving the empty snapshot forever.
        verify(costExplorerClient, times(7)).getReservationPurchaseRecommendation(
                any(GetReservationPurchaseRecommendationRequest.class));
    }

    @Test
    @DisplayName("every reservable service is queried, not just EC2")
    void allReservableServicesAreQueried() {
        when(costExplorerClient.getReservationPurchaseRecommendation(
                any(GetReservationPurchaseRecommendationRequest.class)))
                .thenReturn(GetReservationPurchaseRecommendationResponse.builder().build());

        fetch("NO_UPFRONT");

        // The seven services AWS documents as supporting reservation recommendations:
        // EC2, RDS, ElastiCache, OpenSearch, Redshift, MemoryDB and DynamoDB. DynamoDB was
        // previously absent, so reserved-capacity opportunities were never surfaced.
        verify(costExplorerClient, times(7)).getReservationPurchaseRecommendation(
                any(GetReservationPurchaseRecommendationRequest.class));
    }

    @Test
    @DisplayName("offeringClass is sent in the EC2 service specification, not just the cache key")
    void offeringClassReachesTheRequest() {
        when(costExplorerClient.getReservationPurchaseRecommendation(
                any(GetReservationPurchaseRecommendationRequest.class)))
                .thenReturn(GetReservationPurchaseRecommendationResponse.builder().build());

        service.getPurchaseRecommendationSet(
                account, "ONE_YEAR", "NO_UPFRONT", "THIRTY_DAYS", "CONVERTIBLE", false).join();

        org.mockito.ArgumentCaptor<GetReservationPurchaseRecommendationRequest> captor =
                org.mockito.ArgumentCaptor.forClass(GetReservationPurchaseRecommendationRequest.class);
        verify(costExplorerClient, times(7)).getReservationPurchaseRecommendation(captor.capture());

        GetReservationPurchaseRecommendationRequest ec2Request = captor.getAllValues().stream()
                .filter(r -> r.service().startsWith("Amazon Elastic Compute Cloud"))
                .findFirst()
                .orElseThrow();

        assertThat(ec2Request.serviceSpecification()).isNotNull();
        assertThat(ec2Request.serviceSpecification().ec2Specification().offeringClassAsString())
                .isEqualTo("CONVERTIBLE");

        // Non-EC2 services must not carry an EC2 specification.
        assertThat(captor.getAllValues().stream()
                .filter(r -> !r.service().startsWith("Amazon Elastic Compute Cloud"))
                .allMatch(r -> r.serviceSpecification() == null)).isTrue();
    }
}
