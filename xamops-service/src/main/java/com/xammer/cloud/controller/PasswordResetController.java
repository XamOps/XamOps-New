package com.xammer.cloud.controller;

import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.service.EmailIcons;
import com.xammer.cloud.service.EmailService;
import com.xammer.cloud.service.RedisCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

@RestController
@RequestMapping("/api/auth")
public class PasswordResetController {

    private static final Logger logger = LoggerFactory.getLogger(PasswordResetController.class);

    /** Cryptographically secure source for OTPs — java.util.Random is predictable from its seed. */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** Matches the 5-minute OTP TTL enforced in RedisCacheService.saveOtp. */
    private static final int OTP_TTL_SECONDS = 300;

    /** Wrong-code attempts allowed per issued OTP before the code is burned. */
    private static final int MAX_VERIFY_ATTEMPTS = 5;

    /** OTP requests allowed per email address per window. */
    private static final int MAX_OTP_REQUESTS = 5;
    private static final int OTP_REQUEST_WINDOW_SECONDS = 900; // 15 minutes

    private static String attemptKey(String email) {
        return "OTP_ATTEMPT:" + email;
    }

    private static String requestKey(String email) {
        return "OTP_REQUEST:" + email;
    }

    /**
     * Length-safe, constant-time comparison so response timing does not leak how many
     * leading digits of the OTP were correct.
     */
    private static boolean otpMatches(String expected, String supplied) {
        if (expected == null || supplied == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

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
        try {
            // Throttle before doing any work so this endpoint can't be used to pump out
            // unlimited OTPs (each new OTP is a fresh brute-force window).
            long requests = redisCacheService.incrementCounter(requestKey(email), OTP_REQUEST_WINDOW_SECONDS);
            if (requests > MAX_OTP_REQUESTS) {
                logger.warn("Password reset OTP request throttled for email hash {}", email.hashCode());
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .body("Too many reset requests. Please wait a few minutes and try again.");
            }

            // Find user by email directly — no global_users lookup needed
            userRepository.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("No account found with that email address."));

            // Generate 6-digit OTP from a CSPRNG (full 000000-999999 range)
            String otp = String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));

            // Save OTP to Redis keyed by email (expires in 5 minutes)
            redisCacheService.saveOtp(email, otp);

            // A newly issued code gets a fresh attempt budget, so a user who mistyped the
            // previous one is not locked out of the flow.
            redisCacheService.deleteKey(attemptKey(email));

            // Send Email
            String subject = "XamOps Password Reset Code";
            String body = "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1.0'><title>Password Reset Code</title></head>"
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

            // The OTP is a password-equivalent secret and is deliberately never logged.
            emailService.sendHtmlEmail(email, subject, body);

            return ResponseEntity.ok().body("OTP sent to your email.");

        } catch (Exception e) {
            logger.warn("Password reset OTP request failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body("Error: " + e.getMessage());
        }
    }

    // 2. Verify OTP
    @PostMapping("/verify-otp")
    public ResponseEntity<?> verifyOtp(@RequestParam String email, @RequestParam String otp) {
        String cachedOtp = redisCacheService.getOtp(email);
        if (cachedOtp == null) {
            return ResponseEntity.badRequest().body("Invalid or expired OTP");
        }

        long attempts = redisCacheService.incrementCounter(attemptKey(email), OTP_TTL_SECONDS);
        if (attempts > MAX_VERIFY_ATTEMPTS) {
            // Burn the code so the remaining TTL can't be used to keep guessing.
            redisCacheService.deleteOtp(email);
            logger.warn("Password reset OTP burned after {} failed attempts for email hash {}",
                    attempts - 1, email.hashCode());
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body("Too many invalid attempts. Please request a new code.");
        }

        if (otpMatches(cachedOtp, otp)) {
            redisCacheService.deleteKey(attemptKey(email));
            return ResponseEntity.ok().body("OTP Verified");
        }
        return ResponseEntity.badRequest().body("Invalid or expired OTP");
    }

    // 3. Reset Password
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestParam String email,
                                           @RequestParam String otp,
                                           @RequestParam String newPassword) {
        try {
            // Re-verify OTP
            String cachedOtp = redisCacheService.getOtp(email);
            if (cachedOtp == null) {
                return ResponseEntity.badRequest().body("Invalid request or OTP expired.");
            }

            // Shares the attempt budget with /verify-otp so this endpoint can't be used to
            // bypass the limit and brute-force the code directly.
            long attempts = redisCacheService.incrementCounter(attemptKey(email), OTP_TTL_SECONDS);
            if (attempts > MAX_VERIFY_ATTEMPTS) {
                redisCacheService.deleteOtp(email);
                logger.warn("Password reset OTP burned after {} failed attempts for email hash {}",
                        attempts - 1, email.hashCode());
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .body("Too many invalid attempts. Please request a new code.");
            }

            if (!otpMatches(cachedOtp, otp)) {
                return ResponseEntity.badRequest().body("Invalid request or OTP expired.");
            }

            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            user.setPassword(passwordEncoder.encode(newPassword));
            userRepository.save(user);

            redisCacheService.deleteOtp(email);
            redisCacheService.deleteKey(attemptKey(email));

            return ResponseEntity.ok().body("Password updated successfully.");

        } catch (Exception e) {
            logger.warn("Password reset failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body("Failed to reset password: " + e.getMessage());
        }
    }
}
