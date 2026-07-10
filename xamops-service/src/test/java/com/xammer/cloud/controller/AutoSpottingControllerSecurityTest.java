package com.xammer.cloud.controller;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.AutoSpottingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collections;
import java.util.List;
import java.util.Collection;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class AutoSpottingControllerSecurityTest {

    @Mock
    private AutoSpottingService autoSpottingService;

    @Mock
    private CloudAccountRepository cloudAccountRepository;

    @Mock
    private SecurityContext securityContext;

    @Mock
    private Authentication authentication;

    private ClientUserDetails mockUser;
    private CloudAccount otherAccount;
    private AutoSpottingController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        SecurityContextHolder.setContext(securityContext);
        when(securityContext.getAuthentication()).thenReturn(authentication);

        controller = new AutoSpottingController(autoSpottingService, cloudAccountRepository);

        mockUser = new ClientUserDetails(
                "testuser",
                "password",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")),
                1L, // clientId
                100L, // userId
                "tenant1"
        );

        Client otherClient = new Client();
        otherClient.setId(2L);

        otherAccount = new CloudAccount();
        otherAccount.setAwsAccountId("999999999999");
        otherAccount.setClient(otherClient);
    }

    @Test
    void validateAccountOwnership_whenAccessingOtherClientAccount_thenThrowsAccessDenied() {
        when(authentication.getPrincipal()).thenReturn(mockUser);
        Collection<? extends GrantedAuthority> authorities = mockUser.getAuthorities();
        doReturn(authorities).when(authentication).getAuthorities();

        when(cloudAccountRepository.findByAwsAccountId("999999999999"))
                .thenReturn(List.of(otherAccount));

        assertThrows(AccessDeniedException.class, () -> {
            controller.validateAccountOwnership(999999999999L);
        });
    }

    @Test
    void validateAccountOwnership_whenSuperAdminAccessingOtherClientAccount_thenDoesNotThrow() {
        ClientUserDetails superAdmin = new ClientUserDetails(
                "admin", "pass",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")),
                1L, 1L, "system"
        );
        when(authentication.getPrincipal()).thenReturn(superAdmin);
        Collection<? extends GrantedAuthority> authorities = superAdmin.getAuthorities();
        doReturn(authorities).when(authentication).getAuthorities();

        assertDoesNotThrow(() -> {
            controller.validateAccountOwnership(999999999999L);
        });
    }

    @Test
    void validateAccountOwnership_whenAccessingOwnAccount_thenDoesNotThrow() {
        when(authentication.getPrincipal()).thenReturn(mockUser);
        Collection<? extends GrantedAuthority> authorities = mockUser.getAuthorities();
        doReturn(authorities).when(authentication).getAuthorities();

        Client ownClient = new Client();
        ownClient.setId(1L);
        CloudAccount ownAccount = new CloudAccount();
        ownAccount.setAwsAccountId("111111111111");
        ownAccount.setClient(ownClient);

        when(cloudAccountRepository.findByAwsAccountId("111111111111"))
                .thenReturn(List.of(ownAccount));

        assertDoesNotThrow(() -> {
            controller.validateAccountOwnership(111111111111L);
        });
    }
}
