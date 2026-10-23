package com.xammer.cloud.controller;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.auth.MicrosoftIdentity;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.service.auth.DemoHostResolver;
import com.xammer.cloud.service.auth.MicrosoftOAuthService;
import com.xammer.cloud.service.auth.SessionService;
import com.xammer.cloud.service.auth.TrialEmailService;
import com.xammer.cloud.service.auth.TrialService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for MicrosoftAuthController host-aware OAuth. Mirrors {@link GoogleAuthControllerTest},
 * proving the redirect_uri byte-matches across both OAuth legs (BUG 2) and that the demo-host
 * new-user path auto-provisions onto the demo client while the live path issues a signup token.
 */
class MicrosoftAuthControllerTest {

    private static final String LIVE_REDIRECT = "https://live.xamops.com/api/auth/microsoft/callback";
    private static final String DEMO_REDIRECT = "https://demo.xamops.com/api/auth/microsoft/callback";

    @Mock private MicrosoftOAuthService microsoftOAuthService;
    @Mock private SessionService sessionService;
    @Mock private TrialService trialService;
    @Mock private TrialEmailService trialEmailService;
    @Mock private UserRepository userRepository;
    @Mock private ClientRepository clientRepository;
    @Mock private RedisCacheService redisCacheService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private DemoHostResolver demoHostResolver;

    private MicrosoftAuthController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new MicrosoftAuthController();
        ReflectionTestUtils.setField(controller, "microsoftOAuthService", microsoftOAuthService);
        ReflectionTestUtils.setField(controller, "sessionService", sessionService);
        ReflectionTestUtils.setField(controller, "trialService", trialService);
        ReflectionTestUtils.setField(controller, "trialEmailService", trialEmailService);
        ReflectionTestUtils.setField(controller, "userRepository", userRepository);
        ReflectionTestUtils.setField(controller, "clientRepository", clientRepository);
        ReflectionTestUtils.setField(controller, "redisCacheService", redisCacheService);
        ReflectionTestUtils.setField(controller, "passwordEncoder", passwordEncoder);
        ReflectionTestUtils.setField(controller, "demoHostResolver", demoHostResolver);
        ReflectionTestUtils.setField(controller, "frontendUrl", "https://test.xamops.com");
        ReflectionTestUtils.setField(controller, "liveMicrosoftRedirectUri", LIVE_REDIRECT);
        ReflectionTestUtils.setField(controller, "signupTokenSecret",
                "xamops-google-signup-secret-key-change-me-please-32b");
    }

    private org.springframework.mock.web.MockHttpServletRequest callbackRequest(String storedRedirectUri) {
        org.springframework.mock.web.MockHttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest();
        when(redisCacheService.getString(anyString())).thenReturn(storedRedirectUri);
        when(demoHostResolver.resolveFrontendUrl(any(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        return request;
    }

    @Test
    void microsoftCallback_demoHostNewUser_autoProvisionsOnDemoClient() {
        org.springframework.mock.web.MockHttpServletRequest request = callbackRequest(DEMO_REDIRECT);
        when(demoHostResolver.resolveMicrosoftRedirectUri(any(), any())).thenReturn(DEMO_REDIRECT);
        when(microsoftOAuthService.exchangeCodeForIdentity("code-1", DEMO_REDIRECT))
                .thenReturn(new MicrosoftIdentity("demo-sub", "someone@outlook.com", true, "Some One"));
        when(userRepository.findByMicrosoftSub("demo-sub")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("someone@outlook.com")).thenReturn(Optional.empty());
        when(demoHostResolver.isDemoHost(any())).thenReturn(true);

        Client demoClient = new Client("Demo Workspace");
        demoClient.setId(7L);
        demoClient.setDemo(true);
        when(clientRepository.findFirstByDemoTrue()).thenReturn(Optional.of(demoClient));
        when(passwordEncoder.encode(anyString())).thenReturn("unusable-hash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(sessionService.establishSession(any(), any(), eq("MICROSOFT")))
                .thenReturn(Map.of("redirectUrl", "https://demo.xamops.com/unified-dashboard", "tenantId", "default"));

        var view = controller.microsoftCallback("code-1", "state-1", null, request);

        assertThat(view.getUrl()).isEqualTo("https://demo.xamops.com/unified-dashboard");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User created = userCaptor.getValue();
        assertThat(created.getEmail()).isEqualTo("someone@outlook.com");
        assertThat(created.getRole()).isEqualTo("ROLE_XAMOPS");
        assertThat(created.getAuthProvider()).isEqualTo("MICROSOFT");
        assertThat(created.getMicrosoftSub()).isEqualTo("demo-sub");
        assertThat(created.getClient()).isSameAs(demoClient);
        verify(trialService, never()).startTrial(any());
        verify(trialEmailService, never()).sendWelcome(anyString(), anyString(), any());
    }

    @Test
    void microsoftCallback_nonDemoHostNewUser_issuesSignupToken() {
        org.springframework.mock.web.MockHttpServletRequest request = callbackRequest(LIVE_REDIRECT);
        when(demoHostResolver.resolveMicrosoftRedirectUri(any(), any())).thenReturn(LIVE_REDIRECT);
        when(microsoftOAuthService.exchangeCodeForIdentity("code-2", LIVE_REDIRECT))
                .thenReturn(new MicrosoftIdentity("live-sub", "newbie@corp.com", true, "New Bie"));
        when(userRepository.findByMicrosoftSub("live-sub")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("newbie@corp.com")).thenReturn(Optional.empty());
        when(demoHostResolver.isDemoHost(any())).thenReturn(false);

        var view = controller.microsoftCallback("code-2", "state-2", null, request);

        assertThat(view.getUrl()).startsWith("https://test.xamops.com/signup/complete?token=");
        verify(userRepository, never()).save(any());
        verify(clientRepository, never()).findFirstByDemoTrue();
    }

    // ── OAuth redirect_uri byte-match (BUG 2) ────────────────────────────────

    @Test
    void microsoftLoginAndCallback_demoHost_useSameDemoRedirectUriOnBothLegs() {
        org.springframework.mock.web.MockHttpServletRequest loginReq =
                new org.springframework.mock.web.MockHttpServletRequest();
        when(demoHostResolver.resolveFrontendUrl(any(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(microsoftOAuthService.isConfigured()).thenReturn(true);
        when(demoHostResolver.resolveMicrosoftRedirectUri(any(), any())).thenReturn(DEMO_REDIRECT);
        when(microsoftOAuthService.buildAuthorizationUrl(anyString(), eq(DEMO_REDIRECT)))
                .thenReturn("https://login.microsoftonline.com/common/oauth2/v2.0/authorize?redirect_uri=" + DEMO_REDIRECT);

        controller.microsoftLogin(loginReq);

        ArgumentCaptor<String> storedUri = ArgumentCaptor.forClass(String.class);
        verify(redisCacheService).putString(anyString(), storedUri.capture(), anyInt());
        ArgumentCaptor<String> authLegUri = ArgumentCaptor.forClass(String.class);
        verify(microsoftOAuthService).buildAuthorizationUrl(anyString(), authLegUri.capture());
        assertThat(storedUri.getValue()).isEqualTo(DEMO_REDIRECT);
        assertThat(authLegUri.getValue()).isEqualTo(DEMO_REDIRECT);

        org.springframework.mock.web.MockHttpServletRequest cbReq = callbackRequest(DEMO_REDIRECT);
        when(demoHostResolver.resolveMicrosoftRedirectUri(any(), any())).thenReturn(DEMO_REDIRECT);
        when(microsoftOAuthService.exchangeCodeForIdentity(eq("code-x"), anyString()))
                .thenReturn(new MicrosoftIdentity("sub-x", "x@outlook.com", true, "X"));
        when(userRepository.findByMicrosoftSub("sub-x")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("x@outlook.com")).thenReturn(Optional.empty());
        when(demoHostResolver.isDemoHost(any())).thenReturn(false);

        controller.microsoftCallback("code-x", "state-x", null, cbReq);

        ArgumentCaptor<String> exchangeLegUri = ArgumentCaptor.forClass(String.class);
        verify(microsoftOAuthService).exchangeCodeForIdentity(eq("code-x"), exchangeLegUri.capture());
        assertThat(exchangeLegUri.getValue()).isEqualTo(DEMO_REDIRECT);
        assertThat(exchangeLegUri.getValue()).isEqualTo(authLegUri.getValue());
    }

    @Test
    void microsoftLogin_liveHost_usesLiveRedirectUri() {
        org.springframework.mock.web.MockHttpServletRequest loginReq =
                new org.springframework.mock.web.MockHttpServletRequest();
        when(demoHostResolver.resolveFrontendUrl(any(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(microsoftOAuthService.isConfigured()).thenReturn(true);
        when(demoHostResolver.resolveMicrosoftRedirectUri(any(), eq(LIVE_REDIRECT))).thenReturn(LIVE_REDIRECT);
        when(microsoftOAuthService.buildAuthorizationUrl(anyString(), eq(LIVE_REDIRECT)))
                .thenReturn("https://login.microsoftonline.com/common/oauth2/v2.0/authorize?redirect_uri=" + LIVE_REDIRECT);

        controller.microsoftLogin(loginReq);

        ArgumentCaptor<String> storedUri = ArgumentCaptor.forClass(String.class);
        verify(redisCacheService).putString(anyString(), storedUri.capture(), anyInt());
        assertThat(storedUri.getValue()).isEqualTo(LIVE_REDIRECT);
        verify(microsoftOAuthService).buildAuthorizationUrl(anyString(), eq(LIVE_REDIRECT));
    }
}
