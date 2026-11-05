package com.xammer.cloud.xampulse.service.query;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.dto.XpScope;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryScopeFactoryTest {

    private CloudAccountAccessService access;
    private QueryScopeFactory factory;
    private final ClientUserDetails clientA = new ClientUserDetails("a", "x", List.of(), 1L, 10L, "t");
    private final Instant now = Instant.parse("2026-03-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        access = mock(CloudAccountAccessService.class);
        CloudAccount mine = new CloudAccount();
        mine.setId(100L);
        when(access.requireAccess(any(), eq(100L))).thenReturn(mine);
        when(access.requireAccess(any(), eq(200L))).thenThrow(new AccessDeniedException("Account not found or access denied."));
        when(access.requireAccess(any(), eq(99999L))).thenThrow(new AccessDeniedException("Account not found or access denied."));
        factory = new QueryScopeFactory(new XamPulseAccessGuard(access, mock(CloudAccountRepository.class)), new XamPulseProperties());
    }

    @Test
    void anotherClientsAccountAndAMissingAccountGiveTheIdenticalNotFound() {
        XamPulseException other = assertThrows(XamPulseException.class, () -> factory.build(clientA, List.of(200L), now.minusSeconds(60), now));
        XamPulseException missing = assertThrows(XamPulseException.class, () -> factory.build(clientA, List.of(99999L), now.minusSeconds(60), now));
        assertEquals("XP_NOT_FOUND", other.getCode());
        assertEquals(404, other.getHttpStatus());
        assertEquals(other.getCode(), missing.getCode());
        assertEquals(other.getMessage(), missing.getMessage());
        assertEquals(other.getHttpStatus(), missing.getHttpStatus());
    }

    @Test
    void oneForeignAccountAmongOwnedOnesFailsTheWholeRequest() {
        assertThrows(XamPulseException.class, () -> factory.build(clientA, List.of(100L, 200L), now.minusSeconds(60), now));
    }

    @Test
    void authorisationRunsBeforeRangeValidationSoErrorsLeakNothing() {
        // A foreign account with an absurd range must still be a plain 404, not "query too broad".
        XamPulseException e = assertThrows(XamPulseException.class,
                () -> factory.build(clientA, List.of(200L), now.minus(Duration.ofDays(400)), now));
        assertEquals("XP_NOT_FOUND", e.getCode());
    }

    @Test
    void rangeOverThirtyDaysIsTooBroadWithTheLimitInDetails() {
        XamPulseException e = assertThrows(XamPulseException.class,
                () -> factory.build(clientA, List.of(100L), now.minus(Duration.ofDays(31)), now));
        assertEquals("XP_QUERY_TOO_BROAD", e.getCode());
        assertEquals(30, e.getDetails().get("maxRangeDays"));
        // exactly 30 days is fine
        assertEquals(List.of(100L), factory.build(clientA, List.of(100L), now.minus(Duration.ofDays(30)), now).accountIds());
    }

    @Test
    void invalidRangeAndEmptyAccountsAreRejected() {
        assertThrows(XamPulseException.class, () -> factory.build(clientA, List.of(100L), now, now));
        assertThrows(XamPulseException.class, () -> factory.build(clientA, List.of(100L), now, now.minusSeconds(5)));
        assertThrows(XamPulseException.class, () -> factory.build(clientA, List.of(), now.minusSeconds(5), now));
        assertThrows(XamPulseException.class, () -> factory.build(clientA, null, now.minusSeconds(5), now));
    }

    @Test
    void moreThanTwentyAccountsAreRejectedWithoutTouchingAccessChecks() {
        List<Long> many = new ArrayList<>();
        for (long i = 1; i <= 21; i++) {
            many.add(i);
        }
        assertThrows(XamPulseException.class, () -> factory.build(clientA, many, now.minusSeconds(5), now));
        verify(access, never()).requireAccess(any(), any());
    }

    @Test
    void duplicatesAreCollapsedAndResourceFiltersValidated() {
        QueryScope s = factory.build(clientA, new XpScope(List.of(100L, 100L), List.of("us-east-1"), now.minusSeconds(60), now, null));
        assertEquals(List.of(100L), s.accountIds());
        assertThrows(XamPulseException.class, () -> factory.build(clientA, List.of(100L), now.minusSeconds(60), now,
                null, List.of("x".repeat(201)), null, null));
        assertThrows(XamPulseException.class, () -> factory.build(clientA, List.of(100L), now.minusSeconds(60), now,
                null, List.of("bad\u0000id"), null, null));
    }
}
