package com.xammer.cloud.controller;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.dto.auth.GoogleIdentity;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.service.auth.DemoHostResolver;
import com.xammer.cloud.service.auth.GoogleOAuthService;
import com.xammer.cloud.service.auth.SessionService;
import com.xammer.cloud.service.auth.TrialEmailService;
import com.xammer.cloud.service.auth.TrialService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for GoogleAuthController.completeSignup — validates the two-phase signup
 * (design Property 1: no orphan accounts) and company-name uniqueness suffixing.
 */
class GoogleAuthControllerTest {

    private static final String SECRET = "xamops-google-signup-secret-key-change-me-please-32b";

    @Mock private GoogleOAuthService googleOAuthService;
    @Mock private SessionService sessionService;
    @Mock private TrialService trialService;
    @Mock private TrialEmailService trialEmailService;
    @Mock private UserRepository userRepository;
    @Mock private ClientRepository clientRepository;
    @Mock private RedisCacheService redisCacheService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private DemoHostResolver demoHostResolver;

    private GoogleAuthController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new GoogleAuthController();
        ReflectionTestUtils.setField(controller, "googleOAuthService", googleOAuthService);
        ReflectionTestUtils.setField(controller, "sessionService", sessionService);
        ReflectionTestUtils.setField(controller, "trialService", trialService);
        ReflectionTestUtils.setField(controller, "trialEmailService", trialEmailService);
        ReflectionTestUtils.setField(controller, "userRepository", userRepository);
        ReflectionTestUtils.setField(controller, "clientRepository", clientRepository);
        ReflectionTestUtils.setField(controller, "redisCacheService", redisCacheService);
        ReflectionTestUtils.setField(controller, "passwordEncoder", passwordEncoder);
        ReflectionTestUtils.setField(controller, "demoHostResolver", demoHostResolver);
        ReflectionTestUtils.setField(controller, "frontendUrl", "https://test.xamops.com");
        ReflectionTestUtils.setField(controller, "signupTokenSecret", SECRET);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter())
                .build();
    }

    private String signupToken(String sub, String email) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .setSubject(sub)
                .claim("email", email)
                .claim("name", "Jane Doe")
                .claim("purpose", "GOOGLE_SIGNUP")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 600000))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    @Test
    void completeSignup_missingCompany_returns400() throws Exception {
        String token = signupToken("sub-1", "jane@acme.com");
        mockMvc.perform(post("/api/auth/google/complete-signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"companyName\":\"\"}"))
                .andExpect(status().isBadRequest());
        verify(clientRepository, never()).save(any());
    }

    @Test
    void completeSignup_invalidToken_returns400_noAccountCreated_property1() throws Exception {
        mockMvc.perform(post("/api/auth/google/complete-signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"not-a-jwt\",\"companyName\":\"Acme\"}"))
                .andExpect(status().isBadRequest());
        verify(clientRepository, never()).save(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void completeSignup_newUser_createsClientUserTrialAndWelcome_property1() throws Exception {
        String token = signupToken("sub-2", "jane@acme.com");
        when(userRepository.findByGoogleSub("sub-2")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("jane@acme.com")).thenReturn(Optional.empty());
        when(clientRepository.findByName("Acme")).thenReturn(Optional.empty());
        when(clientRepository.save(any(Client.class))).thenAnswer(inv -> {
            Client c = inv.getArgument(0);
            c.setId(100L);
            return c;
        });
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(passwordEncoder.encode(anyString())).thenReturn("unusable-hash");
        when(sessionService.establishSession(any(), any(), any()))
                .thenReturn(Map.of("redirectUrl", "https://test.xamops.com/unified-dashboard", "tenantId", "default"));

        mockMvc.perform(post("/api/auth/google/complete-signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"companyName\":\"Acme\",\"fullName\":\"Jane Doe\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redirectUrl").value("https://test.xamops.com/unified-dashboard"));

        // A ROLE_XAMOPS, GOOGLE user tied to the new client is created.
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User created = userCaptor.getValue();
        assertThat(created.getRole()).isEqualTo("ROLE_XAMOPS");
        assertThat(created.getAuthProvider()).isEqualTo("GOOGLE");
        assertThat(created.getGoogleSub()).isEqualTo("sub-2");
        // Google users get an unusable (encoded) password so the NOT NULL column is satisfied;
        // password login can never match it.
        assertThat(created.getPassword()).isEqualTo("unusable-hash");

        verify(trialService).startTrial(any(Client.class));
        verify(trialEmailService).sendWelcome(eq("jane@acme.com"), anyString(), any());
    }

    @Test
    void completeSignup_duplicateCompanyName_appendsSuffix_property() throws Exception {
        String token = signupToken("sub-3", "bob@acme.com");
        when(userRepository.findByGoogleSub("sub-3")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("bob@acme.com")).thenReturn(Optional.empty());
        // "Acme" taken, "Acme-2" free
        when(clientRepository.findByName("Acme")).thenReturn(Optional.of(new Client("Acme")));
        when(clientRepository.findByName("Acme-2")).thenReturn(Optional.empty());
        when(clientRepository.save(any(Client.class))).thenAnswer(inv -> {
            Client c = inv.getArgument(0);
            c.setId(101L);
            return c;
        });
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(sessionService.establishSession(any(), any(), any()))
                .thenReturn(Map.of("redirectUrl", "https://test.xamops.com/unified-dashboard", "tenantId", "default"));

        mockMvc.perform(post("/api/auth/google/complete-signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"companyName\":\"Acme\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<Client> clientCaptor = ArgumentCaptor.forClass(Client.class);
        verify(clientRepository).save(clientCaptor.capture());
        assertThat(clientCaptor.getValue().getName()).isEqualTo("Acme-2");
    }

    // ── Demo-host auto-provisioning (FEAT-002) ───────────────────────────────

    private org.springframework.mock.web.MockHttpServletRequest callbackRequest() {
        org.springframework.mock.web.MockHttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest();
        when(redisCacheService.hasKey(anyString())).thenReturn(true);
        return request;
    }

    @Test
    void googleCallback_demoHostNewUser_autoProvisionsOnDemoClient_noSignupToken_ac6_7_8() throws Exception {
        org.springframework.mock.web.MockHttpServletRequest request = callbackRequest();
        when(googleOAuthService.exchangeCodeForIdentity("code-1"))
                .thenReturn(new GoogleIdentity("demo-sub", "someone@gmail.com", true, "Some One", null));
        when(userRepository.findByGoogleSub("demo-sub")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("someone@gmail.com")).thenReturn(Optional.empty());
        when(demoHostResolver.isDemoHost(any())).thenReturn(true);

        Client demoClient = new Client("Demo Workspace");
        demoClient.setId(7L);
        demoClient.setDemo(true);
        when(clientRepository.findFirstByDemoTrue()).thenReturn(Optional.of(demoClient));
        when(passwordEncoder.encode(anyString())).thenReturn("unusable-hash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(sessionService.establishSession(any(), any(), eq("GOOGLE")))
                .thenReturn(Map.of("redirectUrl", "https://test.xamops.com/unified-dashboard", "tenantId", "default"));

        var view = controller.googleCallback("code-1", "state-1", null, request);

        assertThat(view.getUrl()).isEqualTo("https://test.xamops.com/unified-dashboard");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User created = userCaptor.getValue();
        assertThat(created.getEmail()).isEqualTo("someone@gmail.com");
        assertThat(created.getUsername()).isEqualTo("someone@gmail.com");
        assertThat(created.getRole()).isEqualTo("ROLE_XAMOPS");
        assertThat(created.getAuthProvider()).isEqualTo("GOOGLE");
        assertThat(created.getGoogleSub()).isEqualTo("demo-sub");
        assertThat(created.getClient()).isSameAs(demoClient);
        assertThat(created.getLastLoginAt()).isNotNull();
        verify(sessionService).establishSession(any(), any(), eq("GOOGLE"));
        // No signup token path: no trial / welcome on the demo host.
        verify(trialService, never()).startTrial(any());
        verify(trialEmailService, never()).sendWelcome(anyString(), anyString(), any());
    }

    @Test
    void googleCallback_nonDemoHostNewUser_issuesSignupToken_ac12() throws Exception {
        org.springframework.mock.web.MockHttpServletRequest request = callbackRequest();
        when(googleOAuthService.exchangeCodeForIdentity("code-2"))
                .thenReturn(new GoogleIdentity("live-sub", "newbie@corp.com", true, "New Bie", null));
        when(userRepository.findByGoogleSub("live-sub")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("newbie@corp.com")).thenReturn(Optional.empty());
        when(demoHostResolver.isDemoHost(any())).thenReturn(false);

        var view = controller.googleCallback("code-2", "state-2", null, request);

        // Live path is unchanged: short-lived signup token -> /signup/complete, no account created.
        assertThat(view.getUrl()).startsWith("https://test.xamops.com/signup/complete?token=");
        verify(userRepository, never()).save(any());
        verify(clientRepository, never()).findFirstByDemoTrue();
    }

    @Test
    void googleCallback_demoHostButNoDemoClientSeeded_failsClosed() throws Exception {
        org.springframework.mock.web.MockHttpServletRequest request = callbackRequest();
        when(googleOAuthService.exchangeCodeForIdentity("code-3"))
                .thenReturn(new GoogleIdentity("demo-sub-2", "someone2@gmail.com", true, "Some Two", null));
        when(userRepository.findByGoogleSub("demo-sub-2")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("someone2@gmail.com")).thenReturn(Optional.empty());
        when(demoHostResolver.isDemoHost(any())).thenReturn(true);
        when(clientRepository.findFirstByDemoTrue()).thenReturn(Optional.empty());

        var view = controller.googleCallback("code-3", "state-3", null, request);

        assertThat(view.getUrl()).isEqualTo("https://test.xamops.com/login?error=demo_unavailable");
        verify(userRepository, never()).save(any());
        verify(sessionService, never()).establishSession(any(), any(), any());
    }
}
