package com.xammer.cloud.controller;

import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.service.EmailIcons;
import com.xammer.cloud.service.EmailService;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.util.OtpCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * Password reset by emailed one-time code.
 *
 * <p>
 * This flow is an account-takeover primitive if any part of it is weak, because completing
 * it grants control of the account outright. Four properties are load-bearing:
 *
 * <ul>
 *   <li>The code is unpredictable ({@link OtpCodes}, backed by SecureRandom).</li>
 *   <li>Guesses are capped, so ~20 bits of code is not walkable inside the 5-minute TTL.</li>
 *   <li>Responses do not reveal whether an email is registered.</li>
 *   <li>The code never reaches a log or stdout.</li>
 * </ul>
 *
 * The previous implementation had none of them.
 */
@RestController
@RequestMapping("/api/auth")
public class PasswordResetController {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetController.class);

    /** Namespaces the Redis attempt counters so login 2FA and reset do not share a budget. */
    private static final String OTP_SCOPE = "pwreset";

    private static final int MIN_PASSWORD_LENGTH = 8;

    /**
     * Returned for every /forgot-password call regardless of outcome.
     *
     * <p>
     * The old code returned "No account found with that email address." on a miss, which
     * turns this endpoint into a free user-enumeration oracle — useful for targeting the
     * credential stuffing and phishing that follow. The response is now indistinguishable
     * between a registered and unregistered address.
     */
    private static final String GENERIC_SEND_RESPONSE =
            "If an account exists for that address, a reset code has been sent.";

    /**
     * Returned for every failed verification.
     *
     * <p>
     * Deliberately does not distinguish "wrong code" from "expired" from "too many
     * attempts" — those distinctions tell an attacker whether to keep going.
     */
    private static final String GENERIC_OTP_FAILURE = "Invalid or expired code.";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailService emailService;

    @Autowired
    private RedisCacheService redisCacheService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // 1. Send OTP
    @PostMapping("/forgot-password")
    public ResponseEntity<?> sendOtp(@RequestParam String email) {
        String normalizedEmail = normalize(email);
        if (normalizedEmail.isEmpty()) {
            return ResponseEntity.ok().body(GENERIC_SEND_RESPONSE);
        }

        // Rate-limited before the user lookup so the limiter itself cannot be used to
        // distinguish registered addresses by timing or by when throttling kicks in.
        if (redisCacheService.registerOtpRequestAndCheckLimit(OTP_SCOPE, normalizedEmail)) {
            log.warn("Password reset code requests throttled for a single address");
            return ResponseEntity.ok().body(GENERIC_SEND_RESPONSE);
        }

        try {
            Optional<User> user = userRepository.findByEmail(normalizedEmail);
            if (user.isEmpty()) {
                // Same response and no email sent. Returning early here is safe because the
                // response body and status are identical on both paths.
                return ResponseEntity.ok().body(GENERIC_SEND_RESPONSE);
            }

            String otp = OtpCodes.generate();
            redisCacheService.saveOtp(normalizedEmail, otp);

            // A fresh code starts a fresh attempt budget, otherwise a user who mistyped
            // five times could never recover even with a valid new code.
            redisCacheService.clearOtpFailures(OTP_SCOPE, normalizedEmail);

            emailService.sendHtmlEmail(normalizedEmail, "XamOps Password Reset Code", buildEmailBody(otp));

            return ResponseEntity.ok().body(GENERIC_SEND_RESPONSE);

        } catch (Exception e) {
            // Logged without the address or the code, and still answered generically: a 400
            // here would tell the caller their address exists but mail delivery failed.
            log.error("Password reset code dispatch failed: {}", e.getMessage());
            return ResponseEntity.ok().body(GENERIC_SEND_RESPONSE);
        }
    }

    // 2. Verify OTP
    @PostMapping("/verify-otp")
    public ResponseEntity<?> verifyOtp(@RequestParam String email, @RequestParam String otp) {
        String normalizedEmail = normalize(email);

        if (redisCacheService.isOtpLocked(OTP_SCOPE, normalizedEmail)) {
            return tooManyAttempts();
        }

        String cachedOtp = redisCacheService.getOtp(normalizedEmail);
        if (!OtpCodes.matches(cachedOtp, otp)) {
            if (redisCacheService.registerOtpFailure(OTP_SCOPE, normalizedEmail)) {
                // Budget exhausted: burn the code so the remaining window is worthless even
                // if the counter is later cleared.
                redisCacheService.deleteOtp(normalizedEmail);
                log.warn("Password reset code burned after repeated failed attempts");
            }
            return ResponseEntity.badRequest().body(GENERIC_OTP_FAILURE);
        }

        // Intentionally not consumed here — /reset-password re-verifies, and deleting now
        // would break the two-step flow the frontend performs.
        return ResponseEntity.ok().body("OTP Verified");
    }

    // 3. Reset Password
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestParam String email,
                                           @RequestParam String otp,
                                           @RequestParam String newPassword) {
        String normalizedEmail = normalize(email);

        // The cap is enforced here too, not just on /verify-otp. Without this an attacker
        // simply skips the verify step and brute-forces against this endpoint instead,
        // which was possible before.
        if (redisCacheService.isOtpLocked(OTP_SCOPE, normalizedEmail)) {
            return tooManyAttempts();
        }

        try {
            String cachedOtp = redisCacheService.getOtp(normalizedEmail);
            if (!OtpCodes.matches(cachedOtp, otp)) {
                if (redisCacheService.registerOtpFailure(OTP_SCOPE, normalizedEmail)) {
                    redisCacheService.deleteOtp(normalizedEmail);
                }
                return ResponseEntity.badRequest().body(GENERIC_OTP_FAILURE);
            }

            if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
                return ResponseEntity.badRequest()
                        .body("Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
            }

            Optional<User> userOpt = userRepository.findByEmail(normalizedEmail);
            if (userOpt.isEmpty()) {
                // Only reachable if the account was deleted mid-flow.
                redisCacheService.deleteOtp(normalizedEmail);
                return ResponseEntity.badRequest().body(GENERIC_OTP_FAILURE);
            }

            User user = userOpt.get();
            user.setPassword(passwordEncoder.encode(newPassword));
            userRepository.save(user);

            redisCacheService.deleteOtp(normalizedEmail);
            redisCacheService.clearOtpFailures(OTP_SCOPE, normalizedEmail);

            log.info("Password reset completed for user id {}", user.getId());
            return ResponseEntity.ok().body("Password updated successfully.");

        } catch (Exception e) {
            log.error("Password reset failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body("Failed to reset password.");
        }
    }

    private ResponseEntity<?> tooManyAttempts() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body("Too many attempts. Request a new code and try again later.");
    }

    private String normalize(String email) {
        // Keys the Redis code and counters off a canonical form, so alternating case does
        // not hand the caller a separate attempt budget for the same account.
        return email == null ? "" : email.trim().toLowerCase();
    }

    private String buildEmailBody(String otp) {
        return "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1.0'><title>Password Reset Code</title></head>"
               + "<body style='margin:0;padding:0;background:#f1f5f9;font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,Arial,sans-serif;'>"
               + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0'><tr><td style='padding:32px 16px;'>"
               + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' style='max-width:680px;margin:0 auto;background:#ffffff;border-radius:16px;overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,0.08);border:1px solid #e2e8f0;'>"
               + "<tr><td style='background:#000000;padding:40px;'>"
               + "<div style='margin-bottom:16px;'><span style='display:inline-block;background:#ffffff;color:#000000;padding:6px 14px;border-radius:20px;font-size:12px;font-weight:700;letter-spacing:1.5px;text-transform:uppercase;'>XamOps</span></div>"
               + "<div style='margin-bottom:14px;color:#ffffff;'>" + EmailIcons.lock(40) + "</div>"
               + "<h1 style='margin:0 0 6px;color:#fff;font-size:24px;font-weight:700;'>Password Reset Code</h1>"
               + "<p style='margin:0;color:rgba(255,255,255,0.85);font-size:15px;'>XamOps account security</p>"
               + "</td></tr>"
               + "<tr><td style='padding:32px 40px;'>"
               + "<p style='margin:0 0 20px;color:#374151;font-size:15px;'>Use the code below to reset your password. It expires in <strong>5 minutes</strong>.</p>"
               + "<div style='background:#f1f5f9;border-radius:12px;padding:24px;text-align:center;margin-bottom:20px;letter-spacing:0.4em;font-size:36px;font-weight:700;color:#1e293b;font-family:monospace;'>" + otp + "</div>"
               + "<p style='margin:0;color:#64748b;font-size:13px;'>If you did not request a password reset, you can safely ignore this email.</p>"
               + "</td></tr>"
               + "<tr><td style='background:#f8fafc;border-top:1px solid #e2e8f0;padding:24px 40px;text-align:center;'>"
               + "<span style='display:inline-block;background:#000000;padding:7px 18px;border-radius:6px;color:#fff;font-size:13px;font-weight:700;letter-spacing:1px;'>XAMOPS</span>"
               + "<p style='margin:12px 0 4px;color:#64748b;font-size:12px;'>Cloud Cost Management &amp; Security Platform</p>"
               + "<p style='margin:0;color:#cbd5e1;font-size:11px;'>&copy; 2026 XamOps. All rights reserved.</p>"
               + "</td></tr>"
               + "</table></td></tr></table></body></html>";
    }
}
