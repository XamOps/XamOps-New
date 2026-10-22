package com.xammer.cloud.controller;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.EmailService;
import com.xammer.cloud.service.FlexComputeService;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.service.UserActivityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for LoginOtpController using standalone MockMvc.
 *
 * Uses MockMvcBuilders.standaloneSetup() to avoid loading the full Spring context
 * (the application class declares an explicit @ComponentScan that pulls in unrelated
 * beans). All dependencies are mocked and injected via reflection (the controller
 * uses field injection).
 *
 * Covers the two-step login flow:
 *   1. POST /api/auth/initiate-login   — validates the email is known, sends OTP
 *   2. POST /api/auth/verify-login-otp — verifies OTP, creates session
 */
class LoginOtpControllerTest {

    @Mock private UserRepository userRepository;
    @Mock private EmailService emailService;
    @Mock private RedisCacheService redisCacheService;
    @Mock private UserDetailsService userDetailsService;
    @Mock private CloudAccountRepository cloudAccountRepository;
    @Mock private FlexComputeService flexComputeService;
    @Mock private UserActivityService userActivityService;

    private MockMvc mockMvc;
    private User validUser;
    private ClientUserDetails userDetails;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        LoginOtpController controller = new LoginOtpController();
        ReflectionTestUtils.setField(controller, "userRepository", userRepository);
        ReflectionTestUtils.setField(controller, "emailService", emailService);
        ReflectionTestUtils.setField(controller, "redisCacheService", redisCacheService);
        ReflectionTestUtils.setField(controller, "userDetailsService", userDetailsService);
        ReflectionTestUtils.setField(controller, "cloudAccountRepository", cloudAccountRepository);
        ReflectionTestUtils.setField(controller, "flexComputeService", flexComputeService);
        ReflectionTestUtils.setField(controller, "userActivityService", userActivityService);
        ReflectionTestUtils.setField(controller, "frontendUrl", "https://test.xamops.com");

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter())
                .build();

        Client client = new Client();
        client.setId(1L);
        validUser = new User("testuser", "encoded-password", client);
        validUser.setEmail("user@example.com");
        validUser.setRole("ROLE_XAMOPS");

        userDetails = new ClientUserDetails(
                "testuser", "encoded-password",
                List.of(new SimpleGrantedAuthority("ROLE_XAMOPS")),
                1L, 42L, "default");

        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        doNothing().when(emailService).sendHtmlEmail(anyString(), anyString(), anyString());
        when(cloudAccountRepository.findByClientId(anyLong())).thenReturn(List.of());
        doReturn(false).when(flexComputeService).isAuthenticated(anyLong());
    }

    // ── Step 1: /api/auth/initiate-login ──────────────────────────────────────

    @Test
    void initiateLogin_blankEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/initiate-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void initiateLogin_unknownEmail_returns401() throws Exception {
        when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/auth/initiate-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"unknown@example.com\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid email"));
    }

    @Test
    void initiateLogin_knownEmail_sends200WithOtpRequired() throws Exception {
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(validUser));

        mockMvc.perform(post("/api/auth/initiate-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OTP_REQUIRED"));
    }

    @Test
    void initiateLogin_knownEmail_savesOtpInRedis() throws Exception {
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(validUser));

        mockMvc.perform(post("/api/auth/initiate-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\"}"));

        verify(redisCacheService).saveLoginOtp(eq("user@example.com"), anyString());
        verify(redisCacheService).savePreAuthFlag("user@example.com");
    }

    @Test
    void initiateLogin_knownEmail_sendsOtpEmail() throws Exception {
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(validUser));

        mockMvc.perform(post("/api/auth/initiate-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\"}"));

        verify(emailService).sendHtmlEmail(eq("user@example.com"), anyString(), anyString());
    }

    @Test
    void initiateLogin_emailSendFailure_returns500() throws Exception {
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(validUser));
        doThrow(new RuntimeException("SMTP down")).when(emailService)
                .sendHtmlEmail(anyString(), anyString(), anyString());

        mockMvc.perform(post("/api/auth/initiate-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\"}"))
                .andExpect(status().isInternalServerError());
    }

    // ── Step 2: /api/auth/verify-login-otp ────────────────────────────────────

    @Test
    void verifyOtp_blankOtp_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/verify-login-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"otp\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void verifyOtp_noPreAuthFlag_returns401() throws Exception {
        when(redisCacheService.getPreAuthFlag("user@example.com")).thenReturn(false);

        mockMvc.perform(post("/api/auth/verify-login-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"otp\":\"123456\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("Session expired")));
    }

    @Test
    void verifyOtp_wrongOtp_returns401() throws Exception {
        when(redisCacheService.getPreAuthFlag("user@example.com")).thenReturn(true);
        when(redisCacheService.getLoginOtp("user@example.com")).thenReturn("654321");

        mockMvc.perform(post("/api/auth/verify-login-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"otp\":\"000000\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("Invalid or expired")));
    }

    @Test
    void verifyOtp_expiredOtp_nullFromRedis_returns401() throws Exception {
        when(redisCacheService.getPreAuthFlag("user@example.com")).thenReturn(true);
        when(redisCacheService.getLoginOtp("user@example.com")).thenReturn(null);

        mockMvc.perform(post("/api/auth/verify-login-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"otp\":\"123456\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void verifyOtp_correctOtp_cleansRedisKeys() throws Exception {
        when(redisCacheService.getPreAuthFlag("user@example.com")).thenReturn(true);
        when(redisCacheService.getLoginOtp("user@example.com")).thenReturn("123456");
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(validUser));
        when(userDetailsService.loadUserByUsername("testuser")).thenReturn(userDetails);
        when(userRepository.save(any())).thenReturn(validUser);

        mockMvc.perform(post("/api/auth/verify-login-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"otp\":\"123456\"}"));

        verify(redisCacheService).deleteLoginOtp("user@example.com");
        verify(redisCacheService).deletePreAuthFlag("user@example.com");
    }

    @Test
    void verifyOtp_correctOtp_xamopsRole_redirectsToUnifiedDashboard() throws Exception {
        when(redisCacheService.getPreAuthFlag("user@example.com")).thenReturn(true);
        when(redisCacheService.getLoginOtp("user@example.com")).thenReturn("123456");
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(validUser));
        when(userDetailsService.loadUserByUsername("testuser")).thenReturn(userDetails);
        when(userRepository.save(any())).thenReturn(validUser);

        mockMvc.perform(post("/api/auth/verify-login-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"otp\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redirectUrl").value(
                        org.hamcrest.Matchers.containsString("/unified-dashboard")));
    }

    @Test
    void verifyOtp_billopsAdminRole_redirectsToBillopsAdminDashboard() throws Exception {
        ClientUserDetails billopsAdmin = new ClientUserDetails(
                "adminuser", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_BILLOPS_ADMIN")),
                1L, 99L, "default");
        User adminUser = new User("adminuser", "enc-pass", new Client());
        adminUser.setEmail("admin@example.com");

        when(redisCacheService.getPreAuthFlag("admin@example.com")).thenReturn(true);
        when(redisCacheService.getLoginOtp("admin@example.com")).thenReturn("999999");
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(adminUser));
        when(userDetailsService.loadUserByUsername("adminuser")).thenReturn(billopsAdmin);
        when(userRepository.save(any())).thenReturn(adminUser);

        mockMvc.perform(post("/api/auth/verify-login-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@example.com\",\"otp\":\"999999\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redirectUrl").value(
                        org.hamcrest.Matchers.containsString("/billops-admin-dashboard")));
    }

    @Test
    void verifyOtp_billopsRole_redirectsToBillopsDashboard() throws Exception {
        ClientUserDetails billopsUser = new ClientUserDetails(
                "billopsuser", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_BILLOPS")),
                1L, 77L, "default");
        User bUser = new User("billopsuser", "enc", new Client());
        bUser.setEmail("billops@example.com");

        when(redisCacheService.getPreAuthFlag("billops@example.com")).thenReturn(true);
        when(redisCacheService.getLoginOtp("billops@example.com")).thenReturn("111111");
        when(userRepository.findByEmail("billops@example.com")).thenReturn(Optional.of(bUser));
        when(userDetailsService.loadUserByUsername("billopsuser")).thenReturn(billopsUser);
        when(userRepository.save(any())).thenReturn(bUser);

        mockMvc.perform(post("/api/auth/verify-login-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"billops@example.com\",\"otp\":\"111111\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redirectUrl").value(
                        org.hamcrest.Matchers.containsString("/billops-dashboard")));
    }

    @Test
    void verifyOtp_correctOtp_returnsTenantId() throws Exception {
        ClientUserDetails detailsWithTenant = new ClientUserDetails(
                "testuser", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_XAMOPS")),
                1L, 42L, "tenant-acme");

        when(redisCacheService.getPreAuthFlag("user@example.com")).thenReturn(true);
        when(redisCacheService.getLoginOtp("user@example.com")).thenReturn("123456");
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(validUser));
        when(userDetailsService.loadUserByUsername("testuser")).thenReturn(detailsWithTenant);
        when(userRepository.save(any())).thenReturn(validUser);

        mockMvc.perform(post("/api/auth/verify-login-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"otp\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("tenant-acme"));
    }

    @Test
    void verifyOtp_nullTenantId_defaultsToDefaultString() throws Exception {
        ClientUserDetails noTenant = new ClientUserDetails(
                "testuser", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_XAMOPS")),
                1L, 42L, null);

        when(redisCacheService.getPreAuthFlag("user@example.com")).thenReturn(true);
        when(redisCacheService.getLoginOtp("user@example.com")).thenReturn("123456");
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(validUser));
        when(userDetailsService.loadUserByUsername("testuser")).thenReturn(noTenant);
        when(userRepository.save(any())).thenReturn(validUser);

        mockMvc.perform(post("/api/auth/verify-login-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"otp\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("default"));
    }
}
