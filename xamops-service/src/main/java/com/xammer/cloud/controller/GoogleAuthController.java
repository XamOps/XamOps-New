package com.xammer.cloud.controller;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.domain.UserActivityEvent;
import com.xammer.cloud.dto.auth.GoogleIdentity;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.service.auth.DemoHostResolver;
import com.xammer.cloud.service.auth.GoogleOAuthService;
import com.xammer.cloud.service.auth.SessionService;
import com.xammer.cloud.service.auth.TrialEmailService;
import com.xammer.cloud.service.auth.TrialService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

import javax.crypto.SecretKey;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Google Sign-In endpoints. Public (under /api/auth/**).
 *
 * Flow:
 *  - GET  /api/auth/google/login          -> redirect to Google
 *  - GET  /api/auth/google/callback       -> existing user login OR issue signup token
 *  - POST /api/auth/google/complete-signup-> create Client + User + trial, welcome email, session
 *  - GET  /api/auth/trial/status          -> trial state for the current user (popup)
 *  - POST /api/auth/contact-sales         -> email sales@xamops.com
 */
@RestController
@RequestMapping("/api/auth")
@Slf4j
public class GoogleAuthController {

    private static final String SIGNUP_PURPOSE = "GOOGLE_SIGNUP";
    private static final long SIGNUP_TOKEN_TTL_MS = 15 * 60 * 1000L; // 15 minutes
    private static final String STATE_KEY_PREFIX = "google:oauth:state:";

    @Autowired private GoogleOAuthService googleOAuthService;
    @Autowired private SessionService sessionService;
    @Autowired private TrialService trialService;
    @Autowired private TrialEmailService trialEmailService;
    @Autowired private UserRepository userRepository;
    @Autowired private ClientRepository clientRepository;
    @Autowired private RedisCacheService redisCacheService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private DemoHostResolver demoHostResolver;

    @Value("${app.frontend.url}")
    private String frontendUrl;

    // The live/per-profile Google redirect-uri. The controller needs its own copy (the service also
    // injects it as its default) to feed DemoHostResolver, which picks live vs demo per request.
    @Value("${google.oauth.redirect-uri:}")
    private String liveGoogleRedirectUri;

    @Value("${app.signup-token.secret:xamops-google-signup-secret-key-change-me-please-32b}")
    private String signupTokenSecret;

    // ── Step 1: kick off Google OAuth ────────────────────────────────────────
    @GetMapping("/google/login")
    public RedirectView googleLogin(HttpServletRequest request) {
        String base = demoHostResolver.resolveFrontendUrl(request, frontendUrl);
        if (!googleOAuthService.isConfigured()) {
            log.warn("Google login attempted but OAuth is not configured (missing client id/secret/redirect-uri)");
            return new RedirectView(base + "/login?error=google_not_configured");
        }
        try {
            // Pick the redirect-uri for THIS host and commit it to the state entry. The callback
            // leg reads it back so the exchange uses the exact same string (redirect_uri byte-match).
            String redirectUri = demoHostResolver.resolveGoogleRedirectUri(request, liveGoogleRedirectUri);
            String state = UUID.randomUUID().toString();
            redisCacheService.putString(STATE_KEY_PREFIX + state, redirectUri, 10);
            return new RedirectView(googleOAuthService.buildAuthorizationUrl(state, redirectUri));
        } catch (Exception e) {
            // Never 500 the login entry point (e.g. Redis unavailable) — send the user
            // back to the login page with a clear error instead.
            log.error("Failed to initiate Google login: {}", e.getMessage());
            return new RedirectView(base + "/login?error=google_init_failed");
        }
    }

    // ── Step 2: Google redirects back here ───────────────────────────────────
    @GetMapping("/google/callback")
    public RedirectView googleCallback(@RequestParam(value = "code", required = false) String code,
                                       @RequestParam(value = "state", required = false) String state,
                                       @RequestParam(value = "error", required = false) String error,
                                       HttpServletRequest request) {
        String base = demoHostResolver.resolveFrontendUrl(request, frontendUrl);
        if (error != null) {
            return new RedirectView(base + "/login?error=google_denied");
        }
        String storedRedirectUri;
        try {
            storedRedirectUri = code != null && state != null
                    ? redisCacheService.getString(STATE_KEY_PREFIX + state)
                    : null;
        } catch (Exception e) {
            log.error("Google callback state check failed: {}", e.getMessage());
            return new RedirectView(base + "/login?error=google_state");
        }
        if (storedRedirectUri == null) {
            return new RedirectView(base + "/login?error=google_state");
        }
        try { redisCacheService.evict(STATE_KEY_PREFIX + state); } catch (Exception ignored) {}

        // Resolve the redirect-uri for this host and cross-check it against the value committed on
        // the login leg. They must match; prefer the stored value for the exchange so the
        // redirect_uri byte-matches even if detection ever differed between the two legs.
        String resolvedRedirectUri = demoHostResolver.resolveGoogleRedirectUri(request, liveGoogleRedirectUri);
        if (!storedRedirectUri.equals(resolvedRedirectUri)) {
            log.warn("Google redirect-uri mismatch between login and callback legs (stored={}, resolved={}); using stored",
                    storedRedirectUri, resolvedRedirectUri);
        }

        GoogleIdentity identity;
        try {
            identity = googleOAuthService.exchangeCodeForIdentity(code, storedRedirectUri);
        } catch (Exception e) {
            log.warn("Google callback failed: {}", e.getMessage());
            return new RedirectView(base + "/login?error=google_auth_failed");
        }

        // Match by google_sub, then by email (auto-link existing account).
        Optional<User> bySub = userRepository.findByGoogleSub(identity.getSub());
        Optional<User> existing = bySub.isPresent() ? bySub : userRepository.findByEmail(identity.getEmail());

        if (existing.isPresent()) {
            User user = existing.get();
            // First-time Google login on a matched account: link the sub + provider.
            if (user.getGoogleSub() == null) {
                user.setGoogleSub(identity.getSub());
                if (user.getAuthProvider() == null || "LOCAL".equals(user.getAuthProvider())) {
                    // Keep LOCAL users able to use password too; mark GOOGLE only if no password.
                    if (user.getPassword() == null) {
                        user.setAuthProvider("GOOGLE");
                    }
                }
            }
            user.setLastLoginAt(java.time.LocalDateTime.now());
            try { userRepository.save(user); } catch (Exception e) {
                log.warn("Failed to persist Google link for user {}: {}", user.getId(), e.getMessage());
            }
            Map<String, String> result = sessionService.establishSession(user, request, UserActivityEvent.PROVIDER_GOOGLE);
            return new RedirectView(result.get("redirectUrl"));
        }

        // New user — on the demo host, auto-provision onto the shared read-only demo client
        // (no company-name signup flow). The live path below is unchanged.
        if (demoHostResolver.isDemoHost(request)) {
            return new RedirectView(autoProvisionDemoUser(identity, request));
        }

        // New user — do NOT create an account yet. Issue a short-lived signup token.
        String token = issueSignupToken(identity);
        return new RedirectView(base + "/signup/complete?token=" + token);
    }

    /**
     * Demo-host auto-provisioning (plain private helper — NOT @Transactional: the demo client is
     * held as an in-memory managed reference on both branches, so no lazy association is traversed
     * off-session). Creates (or re-uses, race-guarded) a ROLE_XAMOPS user attached to the shared
     * {@code is_demo=true} client, establishes a session (recording exactly one LOGIN row via the
     * existing path), and returns the redirect URL.
     */
    private String autoProvisionDemoUser(GoogleIdentity identity, HttpServletRequest request) {
        // Double-submit / race guard: if the row now exists, just log them in.
        Optional<User> already = userRepository.findByGoogleSub(identity.getSub());
        if (already.isEmpty()) already = userRepository.findByEmail(identity.getEmail());
        if (already.isPresent()) {
            return sessionService.establishSession(already.get(), request,
                    UserActivityEvent.PROVIDER_GOOGLE).get("redirectUrl");
        }
        // Fail closed if the demo client is missing rather than attaching to a real tenant.
        Client demoClient = clientRepository.findFirstByDemoTrue().orElse(null);
        if (demoClient == null) {
            log.warn("Demo host sign-in but no is_demo client is seeded; refusing to provision");
            return demoHostResolver.resolveFrontendUrl(request, frontendUrl) + "/login?error=demo_unavailable";
        }
        User user = new User();
        user.setUsername(identity.getEmail());
        user.setEmail(identity.getEmail());
        // No usable password — demo users authenticate via Google only.
        user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setRole("ROLE_XAMOPS");
        user.setAuthProvider("GOOGLE");
        user.setGoogleSub(identity.getSub());
        user.setClient(demoClient);
        user.setLastLoginAt(java.time.LocalDateTime.now());
        user = userRepository.save(user);
        return sessionService.establishSession(user, request,
                UserActivityEvent.PROVIDER_GOOGLE).get("redirectUrl");
    }

    // ── Step 3: complete signup (create Client + User + trial) ────────────────
    @PostMapping("/google/complete-signup")
    @Transactional
    public ResponseEntity<?> completeSignup(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String token = body.get("token");
        String companyName = body.getOrDefault("companyName", "").trim();
        String fullName = body.getOrDefault("fullName", "").trim();

        if (token == null || token.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing signup token"));
        }
        if (companyName.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Company name is required"));
        }

        Claims claims;
        try {
            claims = parseSignupToken(token);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Signup session expired or invalid. Please sign in with Google again."));
        }
        if (!SIGNUP_PURPOSE.equals(claims.get("purpose", String.class))) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid signup token"));
        }

        String email = claims.get("email", String.class);
        String googleSub = claims.getSubject();
        String googleName = claims.get("name", String.class);

        // Guard against double-submit / race: if the account now exists, just log them in.
        Optional<User> already = userRepository.findByGoogleSub(googleSub);
        if (already.isEmpty()) already = userRepository.findByEmail(email);
        if (already.isPresent()) {
            Map<String, String> result = sessionService.establishSession(already.get(), request, UserActivityEvent.PROVIDER_GOOGLE);
            return ResponseEntity.ok(result);
        }

        // Create the Client (tenant-scoped customer org) with a unique name.
        Client client = new Client();
        client.setName(uniqueClientName(companyName));
        client.setEmail(email);
        client = clientRepository.save(client);

        // Start the 14-day trial on the new Client.
        trialService.startTrial(client);

        // Create the self-signup User: ROLE_XAMOPS, GOOGLE provider, no password.
        User user = new User();
        user.setUsername(email);
        user.setEmail(email);
        // Google users have no usable password. Store an unusable random hash so the
        // (historically NOT NULL) password column is satisfied; password login can
        // never match this, which is correct — they authenticate via Google.
        user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setRole("ROLE_XAMOPS");
        user.setAuthProvider("GOOGLE");
        user.setGoogleSub(googleSub);
        user.setClient(client);
        user.setLastLoginAt(java.time.LocalDateTime.now());
        user = userRepository.save(user);

        // Welcome email (best-effort — never blocks signup).
        try {
            String displayName = !fullName.isEmpty() ? fullName : googleName;
            trialEmailService.sendWelcome(email, displayName, client.getTrialEndAt());
        } catch (Exception e) {
            log.error("Welcome email failed for {}: {}", email, e.getMessage());
        }

        Map<String, String> result = sessionService.establishSession(user, request, UserActivityEvent.PROVIDER_GOOGLE);
        return ResponseEntity.ok(result);
    }

    // ── Trial status for the countdown popup ─────────────────────────────────
    @GetMapping("/trial/status")
    public ResponseEntity<?> trialStatus() {
        ClientUserDetails principal = currentPrincipal();
        if (principal == null || principal.getClientId() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not authenticated"));
        }
        return clientRepository.findById(principal.getClientId())
                .<ResponseEntity<?>>map(client -> ResponseEntity.ok(Map.of(
                        "planType", nullSafe(client.getPlanType()),
                        "trialStatus", nullSafe(trialService.effectiveStatus(client)),
                        "daysRemaining", trialService.daysRemaining(client),
                        "trialEndAt", client.getTrialEndAt() != null ? client.getTrialEndAt().toString() : "")))
                .orElseGet(() -> ResponseEntity.ok(Map.of("planType", "PAID", "trialStatus", "NONE", "daysRemaining", 0)));
    }

    // ── Contact Sales lead ───────────────────────────────────────────────────
    @PostMapping("/contact-sales")
    public ResponseEntity<?> contactSales() {
        ClientUserDetails principal = currentPrincipal();
        if (principal == null || principal.getClientId() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not authenticated"));
        }
        Optional<Client> clientOpt = clientRepository.findById(principal.getClientId());
        Optional<User> userOpt = userRepository.findById(principal.getId());
        String company = clientOpt.map(Client::getName).orElse("Unknown");
        String email = userOpt.map(User::getEmail).orElse(principal.getUsername());
        String name = userOpt.map(User::getUsername).orElse(principal.getUsername());
        long daysRemaining = clientOpt.map(trialService::daysRemaining).orElse(0L);
        try {
            trialEmailService.sendContactSalesLead(name, email, company, daysRemaining);
            return ResponseEntity.ok(Map.of("message", "Our team has been notified and will reach out shortly."));
        } catch (Exception e) {
            log.error("Contact Sales email failed for {}: {}", email, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Could not send your request. Please try again."));
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String uniqueClientName(String base) {
        if (clientRepository.findByName(base).isEmpty()) {
            return base;
        }
        for (int i = 2; i < 1000; i++) {
            String candidate = base + "-" + i;
            if (clientRepository.findByName(candidate).isEmpty()) {
                return candidate;
            }
        }
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String issueSignupToken(GoogleIdentity identity) {
        return Jwts.builder()
                .setSubject(identity.getSub())
                .claim("email", identity.getEmail())
                .claim("name", identity.getName())
                .claim("picture", identity.getPicture())
                .claim("provider", "google")
                .claim("purpose", SIGNUP_PURPOSE)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + SIGNUP_TOKEN_TTL_MS))
                .signWith(signingKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    private Claims parseSignupToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(signingKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(signupTokenSecret.getBytes(StandardCharsets.UTF_8));
    }

    private ClientUserDetails currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof ClientUserDetails) {
            return (ClientUserDetails) auth.getPrincipal();
        }
        return null;
    }

    private static String nullSafe(String s) {
        return s != null ? s : "";
    }
}
