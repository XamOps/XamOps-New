package com.xammer.cloud.xampulse.service.stream;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.xampulse.service.stream.TailAccessRevalidator.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DbTailAccessRevalidatorTest {

    private UserRepository users;
    private CloudAccountAccessService access;
    private DbTailAccessRevalidator revalidator;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        access = mock(CloudAccountAccessService.class);
        revalidator = new DbTailAccessRevalidator(users, access);
    }

    private User user(long id, String role) {
        User u = new User();
        u.setId(id);
        u.setUsername("u" + id);
        u.setRole(role);
        Client c = new Client();
        c.setId(3L);
        u.setClient(c);
        return u;
    }

    @Test
    void activeViewerWithAccessIsAllowed() {
        when(users.findById(5L)).thenReturn(Optional.of(user(5, "XAMOPS")));
        assertEquals(Verdict.ALLOWED, revalidator.check(5L, null, List.of(7L, 8L)));
        verify(access).requireAccess(any(), eq(7L));
        verify(access).requireAccess(any(), eq(8L));
    }

    @Test
    void removedGrantIsDenied() {
        when(users.findById(5L)).thenReturn(Optional.of(user(5, "ROLE_XAMOPS")));
        when(access.requireAccess(any(), eq(8L))).thenThrow(new AccessDeniedException("no"));
        assertEquals(Verdict.DENIED, revalidator.check(5L, null, List.of(7L, 8L)));
    }

    @Test
    void missingUserAndLostRoleAreDenied() {
        when(users.findById(5L)).thenReturn(Optional.empty());
        assertEquals(Verdict.DENIED, revalidator.check(5L, null, List.of(7L)));
        when(users.findById(6L)).thenReturn(Optional.of(user(6, "BILLOPS")));
        assertEquals(Verdict.DENIED, revalidator.check(6L, null, List.of(7L)));
        verifyNoInteractions(access);
    }

    @Test
    void demotedOrMissingActorIsDeniedWhileImpersonating() {
        when(users.findById(5L)).thenReturn(Optional.of(user(5, "XAMOPS")));
        when(users.findById(99L)).thenReturn(Optional.of(user(99, "SUPER_ADMIN")));
        assertEquals(Verdict.ALLOWED, revalidator.check(5L, 99L, List.of(7L)));
        when(users.findById(99L)).thenReturn(Optional.of(user(99, "XAMOPS")));
        assertEquals(Verdict.DENIED, revalidator.check(5L, 99L, List.of(7L)));
        when(users.findById(99L)).thenReturn(Optional.empty());
        assertEquals(Verdict.DENIED, revalidator.check(5L, 99L, List.of(7L)));
    }

    @Test
    void accountMovedToAnotherClientIsDeniedByTheAccessService() {
        when(users.findById(5L)).thenReturn(Optional.of(user(5, "XAMOPS_ADMIN")));
        when(access.requireAccess(any(), eq(7L))).thenThrow(new AccessDeniedException("moved"));
        assertEquals(Verdict.DENIED, revalidator.check(5L, null, List.of(7L)));
    }

    @Test
    void anUnexpectedFailureIsUnknownNotDenied() {
        when(users.findById(5L)).thenThrow(new IllegalStateException("db down"));
        assertEquals(Verdict.UNKNOWN, revalidator.check(5L, null, List.of(7L)));
    }
}
