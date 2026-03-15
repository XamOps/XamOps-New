package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.EmailService;
import com.xammer.cloud.service.FlexComputeService;
import com.xammer.cloud.service.RedisCacheService;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Handles the two-step email+OTP login flow:
 * 1. POST /api/auth/initiate-login  — validates email+password, sends OTP
 * 2. POST /api/auth/verify-login-otp — verifies OTP, creates authenticated session
 */
@RestController
@RequestMapping("/api/auth")
@Slf4j
public class LoginOtpController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

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

    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.frontend.url}")
    private String frontendUrl;

    /**
     * Step 1: Validate email + password, send OTP to email.
     * Body: { "email": "...", "password": "..." }
     */
    @PostMapping("/initiate-login")
    public ResponseEntity<?> initiateLogin(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        String password = body.get("password");

        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email and password are required"));
        }

        email = email.toLowerCase().trim();

        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            log.warn("Login attempt with unknown email: {}", email);
            // Return same error as wrong password to avoid user enumeration
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid email or password"));
        }

        User user = userOpt.get();

        if (!passwordEncoder.matches(password, user.getPassword())) {
            log.warn("Invalid password for email: {}", email);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid email or password"));
        }

        // Credentials validated — generate and send OTP
        String otp = String.format("%06d", secureRandom.nextInt(999999));
        redisCacheService.saveLoginOtp(email, otp);
        redisCacheService.savePreAuthFlag(email);

        String htmlBody = "<div style='font-family:sans-serif;max-width:480px;margin:auto'>"
                + "<h2 style='color:#1a73e8'>XamOps Login Verification</h2>"
                + "<p>Use the following code to complete your sign-in:</p>"
                + "<div style='font-size:2rem;font-weight:bold;letter-spacing:0.3em;"
                + "background:#f1f3f4;padding:16px 24px;border-radius:8px;text-align:center'>"
                + otp + "</div>"
                + "<p style='color:#666;font-size:0.9rem'>This code expires in <strong>5 minutes</strong>. "
                + "Do not share it with anyone.</p>"
                + "</div>";

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

        // Verify OTP
        String cachedOtp = redisCacheService.getLoginOtp(email);
        if (cachedOtp == null || !cachedOtp.equals(otp)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid or expired verification code"));
        }

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

        HttpSession session = request.getSession(true);
        session.setAttribute("SPRING_SECURITY_CONTEXT", securityContext);

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

        String targetUrl;
        if (roles.contains("ROLE_BILLOPS_ADMIN")) {
            targetUrl = frontendUrl + "/billops-admin-dashboard";
        } else if (roles.contains("ROLE_BILLOPS")) {
            targetUrl = frontendUrl + "/billops-dashboard";
        } else if (roles.contains("ROLE_XAMOPS")) {
            targetUrl = frontendUrl + "/unified-dashboard";
        } else if (accounts.isEmpty()) {
            targetUrl = frontendUrl + "/account-manager";
        } else {
            targetUrl = frontendUrl + "/unified-dashboard";
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
}
