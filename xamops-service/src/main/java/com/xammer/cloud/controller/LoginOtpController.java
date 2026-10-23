package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.EmailIcons;
import com.xammer.cloud.service.EmailService;
import com.xammer.cloud.service.FlexComputeService;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.service.UserActivityService;
import com.xammer.cloud.service.auth.DemoHostResolver;
import com.xammer.cloud.domain.UserActivityEvent;
import com.xammer.cloud.util.OtpCodes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Arrays;

/**
 * Handles the two-step email+OTP login flow:
 * 1. POST /api/auth/initiate-login  — validates the email is known, sends OTP
 * 2. POST /api/auth/verify-login-otp — verifies OTP, creates authenticated session
 */
@RestController
@RequestMapping("/api/auth")
@Slf4j
public class LoginOtpController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailService emailService;

    @Autowired
    private RedisCacheService redisCacheService;

    @Autowired
    private UserDetailsService userDetailsService;

    @Autowired
    private CloudAccountRepository cloudAccountRepository;

    @Autowired
    private FlexComputeService flexComputeService;

    @Autowired
    private UserActivityService userActivityService;

    @Autowired
    private DemoHostResolver demoHostResolver;

    @Value("${app.frontend.url}")
    private String frontendUrl;

    private static final Set<String> DEMO_EMAILS = new java.util.HashSet<>(Arrays.asList(
            "demo.xamops@xammer.in",
            "demo.billops@xammer.in"
    ));

    /**
     * Namespaces the OTP attempt counters. Kept distinct from the password-reset scope so
     * failed logins cannot exhaust the reset budget or vice versa.
     */
    private static final String LOGIN_OTP_SCOPE = "login";

    /**
     * Step 1: Validate that the email belongs to a known user, then send an OTP to it.
     * Password is no longer required — the email OTP is the sole login factor.
     * Body: { "email": "..." }
     */
    @PostMapping("/initiate-login")
    public ResponseEntity<?> initiateLogin(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String email = body.get("email");

        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email is required"));
        }

        email = email.toLowerCase().trim();

        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            log.warn("Login attempt with unknown email: {}", email);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid email"));
        }

        User user = userOpt.get();

        // Demo users skip OTP — authenticate directly and return redirectUrl
        if (DEMO_EMAILS.contains(email)) {
            return authenticateAndRedirect(user, request);
        }

        // Credentials validated — generate and send OTP.
        // SecureRandom via OtpCodes: java.util.Random here meant an attacker who could
        // observe one code (their own login) could predict the second factor issued to
        // another user seconds later, which defeats the point of having one.
        String otp = OtpCodes.generate();
        redisCacheService.saveLoginOtp(email, otp);
        redisCacheService.savePreAuthFlag(email);
        // Fresh code, fresh guess budget.
        redisCacheService.clearOtpFailures(LOGIN_OTP_SCOPE, email);

        String htmlBody = "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1.0'><title>Verification Code</title></head>"
                + "<body style='margin:0;padding:0;background:#f1f5f9;font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,Arial,sans-serif;'>"
                + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0'><tr><td style='padding:32px 16px;'>"
                + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' style='max-width:680px;margin:0 auto;background:#ffffff;border-radius:16px;overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,0.08);border:1px solid #e2e8f0;'>"
                + "<tr><td style='background:#000000;padding:40px;'>"
                + "<div style='margin-bottom:16px;'><span style='display:inline-block;background:#ffffff;color:#000000;padding:6px 14px;border-radius:20px;font-size:12px;font-weight:700;letter-spacing:1.5px;text-transform:uppercase;'>XamOps</span></div>"
                + "<div style='margin-bottom:14px;color:#ffffff;'>" + EmailIcons.lock(40) + "</div>"
                + "<h1 style='margin:0 0 6px;color:#fff;font-size:24px;font-weight:700;'>Verification Code</h1>"
                + "<p style='margin:0;color:rgba(255,255,255,0.85);font-size:15px;'>Complete your XamOps sign-in</p>"
                + "</td></tr>"
                + "<tr><td style='padding:32px 40px;'>"
                + "<p style='margin:0 0 20px;color:#374151;font-size:15px;'>Use the code below to complete your sign-in. It expires in <strong>5 minutes</strong>.</p>"
                + "<div style='background:#f1f5f9;border-radius:12px;padding:24px;text-align:center;margin-bottom:20px;letter-spacing:0.4em;font-size:36px;font-weight:700;color:#1e293b;font-family:monospace;'>" + otp + "</div>"
                + "<p style='margin:0;color:#64748b;font-size:13px;'>Do not share this code with anyone. XamOps will never ask for your verification code.</p>"
                + "</td></tr>"
                + "<tr><td style='background:#f8fafc;border-top:1px solid #e2e8f0;padding:24px 40px;text-align:center;'>"
                + "<span style='display:inline-block;background:#000000;padding:7px 18px;border-radius:6px;color:#fff;font-size:13px;font-weight:700;letter-spacing:1px;'>XAMOPS</span>"
                + "<p style='margin:12px 0 4px;color:#64748b;font-size:12px;'>Cloud Cost Management &amp; Security Platform</p>"
                + "<p style='margin:0;color:#cbd5e1;font-size:11px;'>&copy; 2026 XamOps. All rights reserved.</p>"
                + "</td></tr>"
                + "</table></td></tr></table></body></html>";

        try {
            emailService.sendHtmlEmail(email, "XamOps Login Verification Code", htmlBody);
            log.info("Login OTP sent to: {}", email);
        } catch (Exception e) {
            log.error("Failed to send login OTP email to {}: {}", email, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to send verification email. Please try again."));
        }

        return ResponseEntity.ok(Map.of(
                "status", "OTP_REQUIRED",
                "message", "Verification code sent to your email"));
    }

    /**
     * Step 2: Verify OTP and create an authenticated session.
     * Body: { "email": "...", "otp": "..." }
     */
    @PostMapping("/verify-login-otp")
    public ResponseEntity<?> verifyLoginOtp(@RequestBody Map<String, String> body,
                                             HttpServletRequest request) {
        String email = body.get("email");
        String otp = body.get("otp");

        if (email == null || email.isBlank() || otp == null || otp.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email and OTP are required"));
        }

        email = email.toLowerCase().trim();

        // Ensure credentials were validated in step 1
        if (!redisCacheService.getPreAuthFlag(email)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Session expired or invalid request. Please start login again."));
        }

        // Cap guesses. The pre-auth flag proves step 1 succeeded but does nothing to bound
        // how many codes may be tried against it, so without this the second factor is
        // ~20 bits with unlimited attempts inside a 5-minute window.
        if (redisCacheService.isOtpLocked(LOGIN_OTP_SCOPE, email)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Too many attempts. Please start login again."));
        }

        // Verify OTP (constant-time; String.equals leaks a prefix oracle via timing)
        String cachedOtp = redisCacheService.getLoginOtp(email);
        if (!OtpCodes.matches(cachedOtp, otp)) {
            if (redisCacheService.registerOtpFailure(LOGIN_OTP_SCOPE, email)) {
                // Budget spent: invalidate the code and force a full restart rather than
                // leaving a known-length window open.
                redisCacheService.deleteLoginOtp(email);
                redisCacheService.deletePreAuthFlag(email);
                log.warn("Login OTP burned after repeated failed attempts");
            }
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid or expired verification code"));
        }

        redisCacheService.clearOtpFailures(LOGIN_OTP_SCOPE, email);

        // OTP valid — clean up Redis
        redisCacheService.deleteLoginOtp(email);
        redisCacheService.deletePreAuthFlag(email);

        // Load user
        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "User not found"));
        }
        User user = userOpt.get();

        // Record last login time
        try {
            user.setLastLoginAt(java.time.LocalDateTime.now());
            userRepository.save(user);
        } catch (Exception e) {
            log.warn("Failed to record last login time for user {}: {}", user.getId(), e.getMessage());
        }

        // Load full UserDetails (including authorities)
        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getUsername());

        // Create Spring Security authentication and bind to session
        UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(authToken);
        SecurityContextHolder.setContext(securityContext);

        // Invalidate any existing session to prevent session fixation when switching users.
        HttpSession existingSession = request.getSession(false);
        if (existingSession != null) {
            existingSession.invalidate();
        }

        HttpSession session = request.getSession(true);
        session.setAttribute("SPRING_SECURITY_CONTEXT", securityContext);

        // Deliberately after the session exists, not alongside the lastLoginAt write above.
        // That write happens before session fixation handling, so recording there would stamp
        // the event with the id of the session about to be invalidated — and the login could
        // never be paired with its logout.
        userActivityService.recordLogin(user, UserActivityEvent.PROVIDER_OTP, request);

        // Determine redirect URL (same logic as CustomAuthenticationSuccessHandler)
        ClientUserDetails clientDetails = (ClientUserDetails) userDetails;
        Set<String> roles = userDetails.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .collect(Collectors.toSet());

        Long clientId = clientDetails.getClientId();
        List<CloudAccount> accounts;
        try {
            accounts = clientId != null ? cloudAccountRepository.findByClientId(clientId) : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to fetch accounts for clientId {}: {}", clientId, e.getMessage());
            accounts = Collections.emptyList();
        }

        // Host-aware base: demo frontend url on the demo host, else live/per-profile url.
        // Byte-identical to frontendUrl on every non-demo host, so live behavior is unchanged.
        String base = demoHostResolver.resolveFrontendUrl(request, frontendUrl);

        String targetUrl;
        if (roles.contains("ROLE_BILLOPS_ADMIN")) {
            targetUrl = base + "/billops-admin-dashboard";
        } else if (roles.contains("ROLE_BILLOPS")) {
            targetUrl = base + "/billops-dashboard";
        } else if (roles.contains("ROLE_XAMOPS")) {
            targetUrl = base + "/unified-dashboard";
        } else if (accounts.isEmpty()) {
            targetUrl = base + "/account-manager";
        } else {
            targetUrl = base + "/unified-dashboard";
        }

        String tenantId = clientDetails.getTenantId() != null ? clientDetails.getTenantId() : "default";

        // Optional: auto-authenticate with CloudiDR
        try {
            flexComputeService.isAuthenticated(clientDetails.getId());
        } catch (Exception e) {
            log.warn("CloudiDR auto-authentication failed for user {}: {}", clientDetails.getId(), e.getMessage());
        }

        log.info("Login OTP verified for {}. Redirecting to: {}", email, targetUrl);
        return ResponseEntity.ok(Map.of("redirectUrl", targetUrl, "tenantId", tenantId));
    }

    /**
     * Shared helper: creates an authenticated Spring Security session for the given user
     * and returns the same { redirectUrl, tenantId } response used by verifyLoginOtp.
     * Used by demo users who bypass the OTP step.
     */
    private ResponseEntity<?> authenticateAndRedirect(User user, HttpServletRequest request) {
        try {
            UserDetails userDetails = userDetailsService.loadUserByUsername(user.getUsername());

            UsernamePasswordAuthenticationToken authToken =
                    new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
            SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
            securityContext.setAuthentication(authToken);
            SecurityContextHolder.setContext(securityContext);

            HttpSession existingSession = request.getSession(false);
            if (existingSession != null) existingSession.invalidate();
            HttpSession session = request.getSession(true);
            session.setAttribute("SPRING_SECURITY_CONTEXT", securityContext);

            ClientUserDetails clientDetails = (ClientUserDetails) userDetails;
            Set<String> roles = userDetails.getAuthorities().stream()
                    .map(a -> a.getAuthority())
                    .collect(Collectors.toSet());

            Long clientId = clientDetails.getClientId();
            List<CloudAccount> accounts;
            try {
                accounts = clientId != null ? cloudAccountRepository.findByClientId(clientId) : Collections.emptyList();
            } catch (Exception e) {
                accounts = Collections.emptyList();
            }

            // Host-aware base: demo frontend url on the demo host, else live/per-profile url.
            String base = demoHostResolver.resolveFrontendUrl(request, frontendUrl);

            String targetUrl;
            if (roles.contains("ROLE_BILLOPS_ADMIN")) {
                targetUrl = base + "/billops-admin-dashboard";
            } else if (roles.contains("ROLE_BILLOPS")) {
                targetUrl = base + "/billops-dashboard";
            } else if (roles.contains("ROLE_XAMOPS")) {
                targetUrl = base + "/unified-dashboard";
            } else if (accounts.isEmpty()) {
                targetUrl = base + "/account-manager";
            } else {
                targetUrl = base + "/unified-dashboard";
            }

            String tenantId = clientDetails.getTenantId() != null ? clientDetails.getTenantId() : "default";

            try {
                flexComputeService.isAuthenticated(clientDetails.getId());
            } catch (Exception e) {
                log.warn("CloudiDR auto-auth failed for demo user {}: {}", clientDetails.getId(), e.getMessage());
            }

            log.info("Demo login for {}. Redirecting to: {}", user.getEmail(), targetUrl);
            return ResponseEntity.ok(Map.of("redirectUrl", targetUrl, "tenantId", tenantId));
        } catch (Exception e) {
            log.error("Demo login failed for {}: {}", user.getEmail(), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Login failed. Please try again."));
        }
    }
}
