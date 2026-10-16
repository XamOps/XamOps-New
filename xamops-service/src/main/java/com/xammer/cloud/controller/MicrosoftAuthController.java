package com.xammer.cloud.controller;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.domain.UserActivityEvent;
import com.xammer.cloud.dto.auth.MicrosoftIdentity;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.service.auth.MicrosoftOAuthService;
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
 * Microsoft (Entra ID / Azure AD) Sign-In endpoints. Public (under /api/auth/**).
 * Mirrors {@link GoogleAuthController}.
 *
 * Flow:
 *  - GET  /api/auth/microsoft/login           -> redirect to Microsoft
 *  - GET  /api/auth/microsoft/callback        -> existing user login OR issue signup token
 *  - POST /api/auth/microsoft/complete-signup -> create Client + User + trial, welcome email, session
 */
@RestController
@RequestMapping("/api/auth")
@Slf4j
public class MicrosoftAuthController {

    private static final String SIGNUP_PURPOSE = "MICROSOFT_SIGNUP";
    private static final long SIGNUP_TOKEN_TTL_MS = 15 * 60 * 1000L; // 15 minutes
    private static final String STATE_KEY_PREFIX = "microsoft:oauth:state:";

    @Autowired private MicrosoftOAuthService microsoftOAuthService;
    @Autowired private SessionService sessionService;
    @Autowired private TrialService trialService;
    @Autowired private TrialEmailService trialEmailService;
    @Autowired private UserRepository userRepository;
    @Autowired private ClientRepository clientRepository;
    @Autowired private RedisCacheService redisCacheService;
    @Autowired private PasswordEncoder passwordEncoder;

    @Value("${app.frontend.url}")
    private String frontendUrl;

    // Reuse the same signup-token secret as Google so a single frontend page works.
    @Value("${app.signup-token.secret:xamops-google-signup-secret-key-change-me-please-32b}")
    private String signupTokenSecret;

    // ── Step 1: kick off Microsoft OAuth ─────────────────────────────────────
    @GetMapping("/microsoft/login")
    public RedirectView microsoftLogin() {
        if (!microsoftOAuthService.isConfigured()) {
            log.warn("Microsoft login attempted but OAuth is not configured (missing client id/secret/redirect-uri)");
            return new RedirectView(frontendUrl + "/login?error=microsoft_not_configured");
        }
        try {
            String state = UUID.randomUUID().toString();
            redisCacheService.putString(STATE_KEY_PREFIX + state, "1", 10);
            return new RedirectView(microsoftOAuthService.buildAuthorizationUrl(state));
        } catch (Exception e) {
            log.error("Failed to initiate Microsoft login: {}", e.getMessage());
            return new RedirectView(frontendUrl + "/login?error=microsoft_init_failed");
        }
    }

    // ── Step 2: Microsoft redirects back here ────────────────────────────────
    @GetMapping("/microsoft/callback")
    public RedirectView microsoftCallback(@RequestParam(value = "code", required = false) String code,
                                          @RequestParam(value = "state", required = false) String state,
                                          @RequestParam(value = "error", required = false) String error,
                                          HttpServletRequest request) {
        if (error != null) {
            return new RedirectView(frontendUrl + "/login?error=microsoft_denied");
        }
        boolean stateValid;
        try {
            stateValid = code != null && state != null && redisCacheService.hasKey(STATE_KEY_PREFIX + state);
        } catch (Exception e) {
            log.error("Microsoft callback state check failed: {}", e.getMessage());
            return new RedirectView(frontendUrl + "/login?error=microsoft_state");
        }
        if (!stateValid) {
            return new RedirectView(frontendUrl + "/login?error=microsoft_state");
        }
        try { redisCacheService.evict(STATE_KEY_PREFIX + state); } catch (Exception ignored) {}

        MicrosoftIdentity identity;
        try {
            identity = microsoftOAuthService.exchangeCodeForIdentity(code);
        } catch (Exception e) {
            log.warn("Microsoft callback failed: {}", e.getMessage());
            return new RedirectView(frontendUrl + "/login?error=microsoft_auth_failed");
        }

        // Match by microsoft_sub, then by email (auto-link existing account).
        Optional<User> bySub = userRepository.findByMicrosoftSub(identity.getSub());
        Optional<User> existing = bySub.isPresent() ? bySub : userRepository.findByEmail(identity.getEmail());

        if (existing.isPresent()) {
            User user = existing.get();
            // First-time Microsoft login on a matched account: link the sub + provider.
            if (user.getMicrosoftSub() == null) {
                user.setMicrosoftSub(identity.getSub());
                if (user.getAuthProvider() == null || "LOCAL".equals(user.getAuthProvider())) {
                    if (user.getPassword() == null) {
                        user.setAuthProvider("MICROSOFT");
                    }
                }
            }
            user.setLastLoginAt(java.time.LocalDateTime.now());
            try { userRepository.save(user); } catch (Exception e) {
                log.warn("Failed to persist Microsoft link for user {}: {}", user.getId(), e.getMessage());
            }
            Map<String, String> result = sessionService.establishSession(user, request, UserActivityEvent.PROVIDER_MICROSOFT);
            return new RedirectView(result.get("redirectUrl"));
        }

        // New user — do NOT create an account yet. Issue a short-lived signup token.
        String token = issueSignupToken(identity);
        return new RedirectView(frontendUrl + "/signup/complete?token=" + token);
    }

    // ── Step 3: complete signup (create Client + User + trial) ───────────────
    @PostMapping("/microsoft/complete-signup")
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
                    .body(Map.of("error", "Signup session expired or invalid. Please sign in with Microsoft again."));
        }
        if (!SIGNUP_PURPOSE.equals(claims.get("purpose", String.class))) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid signup token"));
        }

        String email = claims.get("email", String.class);
        String microsoftSub = claims.getSubject();
        String microsoftName = claims.get("name", String.class);

        // Guard against double-submit / race: if the account now exists, just log them in.
        Optional<User> already = userRepository.findByMicrosoftSub(microsoftSub);
        if (already.isEmpty()) already = userRepository.findByEmail(email);
        if (already.isPresent()) {
            Map<String, String> result = sessionService.establishSession(already.get(), request, UserActivityEvent.PROVIDER_MICROSOFT);
            return ResponseEntity.ok(result);
        }

        Client client = new Client();
        client.setName(uniqueClientName(companyName));
        client.setEmail(email);
        client = clientRepository.save(client);

        trialService.startTrial(client);

        User user = new User();
        user.setUsername(email);
        user.setEmail(email);
        // Microsoft users have no usable password. Store an unusable random hash so
        // password login can never match — they authenticate via Microsoft.
        user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setRole("ROLE_XAMOPS");
        user.setAuthProvider("MICROSOFT");
        user.setMicrosoftSub(microsoftSub);
        user.setClient(client);
        user.setLastLoginAt(java.time.LocalDateTime.now());
        user = userRepository.save(user);

        try {
            String displayName = !fullName.isEmpty() ? fullName : microsoftName;
            trialEmailService.sendWelcome(email, displayName, client.getTrialEndAt());
        } catch (Exception e) {
            log.error("Welcome email failed for {}: {}", email, e.getMessage());
        }

        Map<String, String> result = sessionService.establishSession(user, request, UserActivityEvent.PROVIDER_MICROSOFT);
        return ResponseEntity.ok(result);
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

    private String issueSignupToken(MicrosoftIdentity identity) {
        return Jwts.builder()
                .setSubject(identity.getSub())
                .claim("email", identity.getEmail())
                .claim("name", identity.getName())
                .claim("provider", "microsoft")
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
}
