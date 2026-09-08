package com.xammer.billops.service;

import com.xammer.billops.dto.CreditRequestDto;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.repository.CreditRequestRepository;
import com.xammer.billops.repository.UserRepository;
import com.xammer.cloud.domain.CreditRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for CreditRequestService.applyAccountFilter() — the account
 * scoping logic — and for the stage an admin moves a request to. Other methods require DB and
 * email, so they are integration concerns.
 */
class CreditRequestServiceTest {

    @Mock private CreditRequestRepository creditRequestRepository;
    @Mock private UserRepository userRepository;
    @Mock private EmailService emailService;
    @Mock private AccountAccessHelper accountAccessHelper;
    @Mock private CloudAccountRepository cloudAccountRepository;

    private CreditRequestService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new CreditRequestService();
        // Inject mocks via reflection
        org.springframework.test.util.ReflectionTestUtils.setField(service, "creditRequestRepository", creditRequestRepository);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "userRepository", userRepository);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "emailService", emailService);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "accountAccessHelper", accountAccessHelper);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "cloudAccountRepository", cloudAccountRepository);
    }

    // ── applyAccountFilter ─────────────────────────────────────────────────

    @Test
    void applyAccountFilter_nullAllowedIds_returnsAllRequests() {
        // null from AccountAccessHelper = primary admin = no filter
        when(accountAccessHelper.getAllowedProviderAccountIds()).thenReturn(null);

        List<CreditRequestDto> all = List.of(
                request("111111111111"), request("222222222222"));

        List<CreditRequestDto> result = service.applyAccountFilter(all);

        assertThat(result).hasSize(2);
    }

    @Test
    void applyAccountFilter_emptyAllowedIds_returnsNoRequests() {
        when(accountAccessHelper.getAllowedProviderAccountIds()).thenReturn(Collections.emptySet());

        List<CreditRequestDto> all = List.of(
                request("111111111111"), request("222222222222"));

        List<CreditRequestDto> result = service.applyAccountFilter(all);

        assertThat(result).isEmpty();
    }

    @Test
    void applyAccountFilter_specificAccountIds_returnsOnlyAllowed() {
        when(accountAccessHelper.getAllowedProviderAccountIds())
                .thenReturn(Set.of("111111111111"));

        List<CreditRequestDto> all = List.of(
                request("111111111111"),
                request("222222222222"),
                request("333333333333"));

        List<CreditRequestDto> result = service.applyAccountFilter(all);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAwsAccountId()).isEqualTo("111111111111");
    }

    @Test
    void applyAccountFilter_multipleAllowedAccounts_returnsAll() {
        when(accountAccessHelper.getAllowedProviderAccountIds())
                .thenReturn(Set.of("111111111111", "333333333333"));

        List<CreditRequestDto> all = List.of(
                request("111111111111"),
                request("222222222222"),
                request("333333333333"));

        List<CreditRequestDto> result = service.applyAccountFilter(all);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(CreditRequestDto::getAwsAccountId)
                .containsExactlyInAnyOrder("111111111111", "333333333333");
    }

    @Test
    void applyAccountFilter_requestWithNullAccountId_excluded() {
        when(accountAccessHelper.getAllowedProviderAccountIds())
                .thenReturn(Set.of("111111111111"));

        CreditRequestDto nullAcct = new CreditRequestDto();
        nullAcct.setAwsAccountId(null);  // null account ID

        List<CreditRequestDto> result = service.applyAccountFilter(List.of(nullAcct));

        assertThat(result).isEmpty();
    }

    @Test
    void applyAccountFilter_emptyInput_returnsEmptyList() {
        when(accountAccessHelper.getAllowedProviderAccountIds())
                .thenReturn(Set.of("111111111111"));

        assertThat(service.applyAccountFilter(Collections.emptyList())).isEmpty();
    }

    // ── updateRequestStatus ───────────────────────────────────────────────────

    @Test
    void updateRequestStatus_knownStage_isStored() {
        CreditRequest stored = storedRequest("submitted");

        CreditRequestDto result = service.updateRequestStatus(1L, "business_approval");

        assertThat(stored.getStatus()).isEqualTo("business_approval");
        assertThat(result.getStatus()).isEqualTo("business_approval");
    }

    @Test
    void updateRequestStatus_preRenameStage_isTranslated() {
        // Clients that still send the old stage land on the one that replaced it
        CreditRequest stored = storedRequest("documentation_review");

        CreditRequestDto result = service.updateRequestStatus(1L, "technical_verification");

        assertThat(stored.getStatus()).isEqualTo("business_approval");
        assertThat(result.getStatus()).isEqualTo("business_approval");
    }

    @Test
    void updateRequestStatus_unknownStage_isRejectedAndNotStored() {
        assertThatThrownBy(() -> service.updateRequestStatus(1L, "in_progress"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(creditRequestRepository, never()).save(any());
    }

    @Test
    void listedRequests_preRenameStoredStage_areReadBackAsBusinessApproval() {
        // Rows written before the rename are never rewritten, so the read path translates them
        CreditRequest stored = new CreditRequest();
        stored.setId(1L);
        stored.setAwsAccountId("111111111111");
        stored.setStatus("technical_verification");
        when(creditRequestRepository.findByUserId(7L)).thenReturn(List.of(stored));
        when(cloudAccountRepository.findByAnyCloudIdIn(any())).thenReturn(Collections.emptyList());

        List<CreditRequestDto> result = service.getCreditRequestsByUserIdAndCache(7L);

        assertThat(result).singleElement()
                .extracting(CreditRequestDto::getStatus)
                .isEqualTo("business_approval");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private CreditRequestDto request(String accountId) {
        CreditRequestDto dto = new CreditRequestDto();
        dto.setAwsAccountId(accountId);
        return dto;
    }

    /** A persisted request at the given stage, wired into the repository mock as id 1. */
    private CreditRequest storedRequest(String status) {
        CreditRequest creditRequest = new CreditRequest();
        creditRequest.setId(1L);
        creditRequest.setAwsAccountId("111111111111");
        creditRequest.setStatus(status);
        when(creditRequestRepository.findById(1L)).thenReturn(Optional.of(creditRequest));
        when(creditRequestRepository.save(any(CreditRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return creditRequest;
    }
}
