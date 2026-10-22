package com.xammer.billops.service;

import com.xammer.billops.domain.Client;
import com.xammer.billops.dto.UserDTO;
import com.xammer.billops.repository.AppUserRepository;
import com.xammer.billops.repository.ClientRepository;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.repository.UserCloudAccountAccessRepository;
import com.xammer.billops.repository.UserTabAccessRepository;
import com.xammer.cloud.domain.AppUser;
import com.xammer.cloud.messaging.MessagingProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AppUserService#createUser} proving a user / sub-user can be created
 * without a password. Accounts authenticate via email + OTP, so create must store a non-null,
 * unusable BCrypt hash rather than requiring or emailing a password.
 */
class AppUserServiceCreateTest {

    @Mock private AppUserRepository appUserRepository;
    @Mock private ClientRepository clientRepository;
    @Mock private UserTabAccessRepository userTabAccessRepository;
    @Mock private UserCloudAccountAccessRepository userCloudAccountAccessRepository;
    @Mock private CloudAccountRepository cloudAccountRepository;
    @Mock private JavaMailSender mailSender;
    @Mock private EmailService emailService;
    @Mock private MessagingProperties messagingProperties;

    private AppUserService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new AppUserService();
        ReflectionTestUtils.setField(service, "appUserRepository", appUserRepository);
        ReflectionTestUtils.setField(service, "clientRepository", clientRepository);
        ReflectionTestUtils.setField(service, "userTabAccessRepository", userTabAccessRepository);
        ReflectionTestUtils.setField(service, "userCloudAccountAccessRepository", userCloudAccountAccessRepository);
        ReflectionTestUtils.setField(service, "cloudAccountRepository", cloudAccountRepository);
        ReflectionTestUtils.setField(service, "mailSender", mailSender);
        ReflectionTestUtils.setField(service, "passwordEncoder", new BCryptPasswordEncoder());
        ReflectionTestUtils.setField(service, "emailService", emailService);
        ReflectionTestUtils.setField(service, "messagingProperties", messagingProperties);

        when(appUserRepository.findByUsername(any())).thenReturn(Optional.empty());
        when(clientRepository.findByName(any())).thenReturn(new Client("Acme"));
        when(appUserRepository.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        // No authenticated caller — createUser reads SecurityContextHolder and must tolerate null.
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createUser_withNullPassword_storesUnusableHash() {
        UserDTO dto = baseDto();
        dto.setPassword(null);

        service.createUser(dto);

        AppUser saved = captureSavedUser();
        assertThat(saved.getPassword()).isNotNull().isNotBlank();
        assertThat(saved.getPassword()).startsWith("$2"); // BCrypt hash, not the literal input
    }

    @Test
    void createUser_withBlankPassword_storesUnusableHash() {
        UserDTO dto = baseDto();
        dto.setUsername("newuser2");
        dto.setPassword("");

        service.createUser(dto);

        AppUser saved = captureSavedUser();
        assertThat(saved.getPassword()).isNotNull().isNotBlank();
        assertThat(saved.getPassword()).startsWith("$2");
        assertThat(saved.getPassword()).isNotEqualTo("");
    }

    private UserDTO baseDto() {
        UserDTO dto = new UserDTO();
        dto.setUsername("newuser");
        dto.setEmail("newuser@acme.test");
        dto.setRole("ROLE_BILLOPS");
        dto.setClientName("Acme");
        return dto;
    }

    private AppUser captureSavedUser() {
        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        org.mockito.Mockito.verify(appUserRepository).save(captor.capture());
        return captor.getValue();
    }
}
