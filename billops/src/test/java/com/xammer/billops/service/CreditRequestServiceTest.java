package com.xammer.billops.service;

import com.xammer.billops.dto.CreditRequestDto;
import com.xammer.billops.repository.CreditRequestRepository;
import com.xammer.billops.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for CreditRequestService.applyAccountFilter() — the account
 * scoping logic. Other methods require DB and email, so they are integration concerns.
 */
class CreditRequestServiceTest {

    @Mock private CreditRequestRepository creditRequestRepository;
    @Mock private UserRepository userRepository;
    @Mock private EmailService emailService;
    @Mock private AccountAccessHelper accountAccessHelper;

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

    // ── Helper ────────────────────────────────────────────────────────────────

    private CreditRequestDto request(String accountId) {
        CreditRequestDto dto = new CreditRequestDto();
        dto.setAwsAccountId(accountId);
        return dto;
    }
}
