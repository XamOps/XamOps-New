package com.xammer.billops.service;

import com.xammer.billops.domain.Client;
import com.xammer.billops.dto.TeamUserDTO;
import com.xammer.billops.repository.AppUserRepository;
import com.xammer.billops.repository.ClientRepository;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.repository.UserCloudAccountAccessRepository;
import com.xammer.billops.repository.UserTabAccessRepository;
import com.xammer.cloud.domain.AppUser;
import com.xammer.cloud.security.ClientUserDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TeamUserService#createTeamUser} proving a team member can be onboarded
 * without a password. Accounts authenticate via email + OTP, so create must store a non-null,
 * unusable BCrypt hash rather than requiring or emailing a password.
 */
class TeamUserServiceCreateTest {

    @Mock private AppUserRepository appUserRepository;
    @Mock private ClientRepository clientRepository;
    @Mock private CloudAccountRepository cloudAccountRepository;
    @Mock private UserCloudAccountAccessRepository userCloudAccountAccessRepository;
    @Mock private UserTabAccessRepository userTabAccessRepository;
    @Mock private EmailService emailService;

    private TeamUserService service;

    private static final Long PRIMARY_ID = 42L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new TeamUserService();
        ReflectionTestUtils.setField(service, "appUserRepository", appUserRepository);
        ReflectionTestUtils.setField(service, "clientRepository", clientRepository);
        ReflectionTestUtils.setField(service, "cloudAccountRepository", cloudAccountRepository);
        ReflectionTestUtils.setField(service, "userCloudAccountAccessRepository", userCloudAccountAccessRepository);
        ReflectionTestUtils.setField(service, "userTabAccessRepository", userTabAccessRepository);
        ReflectionTestUtils.setField(service, "passwordEncoder", new BCryptPasswordEncoder());
        ReflectionTestUtils.setField(service, "emailService", emailService);

        // Primary admin (createdBy == null) performing the creation.
        Client client = new Client("Acme");
        AppUser primary = new AppUser("admin", "x", "admin@acme.test", "ROLE_BILLOPS_ADMIN", client);
        primary.setId(PRIMARY_ID);
        primary.setCreatedBy(null);
        when(appUserRepository.findById(PRIMARY_ID)).thenReturn(Optional.of(primary));
        when(appUserRepository.findByUsername(any())).thenReturn(Optional.empty());
        when(appUserRepository.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        // Seed an authenticated primary user into the security context.
        ClientUserDetails principal = new ClientUserDetails(
                "admin", "x",
                List.of(new SimpleGrantedAuthority("ROLE_BILLOPS_ADMIN")),
                1L, PRIMARY_ID);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "x", principal.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createTeamUser_withNullPassword_storesUnusableHash() {
        TeamUserDTO dto = new TeamUserDTO();
        dto.setUsername("teammate");
        dto.setEmail("teammate@acme.test");
        dto.setPassword(null);

        service.createTeamUser(dto);

        AppUser saved = captureSavedUser();
        assertThat(saved.getPassword()).isNotNull().isNotBlank();
        assertThat(saved.getPassword()).startsWith("$2"); // BCrypt hash, not the literal input
    }

    @Test
    void createTeamUser_withBlankPassword_storesUnusableHash() {
        TeamUserDTO dto = new TeamUserDTO();
        dto.setUsername("teammate2");
        dto.setEmail("teammate2@acme.test");
        dto.setPassword("");

        service.createTeamUser(dto);

        AppUser saved = captureSavedUser();
        assertThat(saved.getPassword()).isNotNull().isNotBlank();
        assertThat(saved.getPassword()).startsWith("$2");
        assertThat(saved.getPassword()).isNotEqualTo("");
    }

    private AppUser captureSavedUser() {
        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        org.mockito.Mockito.verify(appUserRepository).save(captor.capture());
        return captor.getValue();
    }
}
