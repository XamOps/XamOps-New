package com.xammer.cloud.controller;

import com.xammer.cloud.config.multitenancy.TenantContext;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.GlobalUserDto;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.service.EmailService;
import com.xammer.cloud.service.MasterDatabaseService;
import com.xammer.cloud.service.RedisCacheService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.security.SecureRandom;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class PasswordResetController {

    private static final Logger logger = LoggerFactory.getLogger(PasswordResetController.class);
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailService emailService;

    @Autowired
    private RedisCacheService redisCacheService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MasterDatabaseService masterDatabaseService;

    /**
     * Helper: Looks up the user in the Global Master DB to find their Tenant ID,
     * then switches the current thread's context to that Tenant.
     */
    private boolean setTenantContextForUser(String username) {
        // 1. Global Lookup
        Optional<GlobalUserDto> globalUserOpt = masterDatabaseService.findGlobalUser(username);
        
        if (globalUserOpt.isPresent()) {
            String tenantId = globalUserOpt.get().getTenantId();
            // 2. Context Switch
            TenantContext.setCurrentTenant(tenantId);
            return true;
        }
        return false;
    }

    // 1. Send OTP
    @PostMapping("/forgot-password")
    public ResponseEntity<?> sendOtp(@RequestParam String username, @RequestParam String email) {
        try {
            // ✅ FIX: Switch to the correct database for this user
            boolean userFound = setTenantContextForUser(username);

            if (userFound) {
                // Find user in their specific Tenant DB
                Optional<User> userOpt = userRepository.findByUsername(username);

                // Verify email matches
                if (userOpt.isPresent() && userOpt.get().getEmail() != null &&
                    userOpt.get().getEmail().equalsIgnoreCase(email)) {

                    // Generate cryptographically secure 6-digit OTP
                    String otp = String.format("%06d", secureRandom.nextInt(1000000));

                    // Save OTP to Redis (Expires in 5 minutes)
                    redisCacheService.saveOtp(username, otp);

                    // Send Email
                    String subject = "BillOps Password Reset Code";
                    String body = "<h3>Password Reset Request</h3>" +
                                  "<p>Your verification code is: <strong>" + otp + "</strong></p>" +
                                  "<p>This code expires in 5 minutes.</p>";

                    emailService.sendHtmlEmail(email, subject, body);
                }
            }

            // Return generic message regardless of user existence to prevent enumeration
            return ResponseEntity.ok().body("If your account exists, an OTP has been sent to your email.");

        } catch (Exception e) {
            logger.error("Failed to process forgot-password request for user: {}", username, e);
            return ResponseEntity.ok().body("If your account exists, an OTP has been sent to your email.");
        }
    }

    // 2. Verify OTP
    @PostMapping("/verify-otp")
    public ResponseEntity<?> verifyOtp(@RequestParam String username, @RequestParam String otp) {
        // Redis is global/shared, so strictly speaking we don't NEED the tenant context here,
        // but it doesn't hurt.
        String cachedOtp = redisCacheService.getOtp(username);
        
        if (cachedOtp != null && cachedOtp.equals(otp)) {
            return ResponseEntity.ok().body("OTP Verified");
        }
        return ResponseEntity.badRequest().body("Invalid or expired OTP");
    }

    // 3. Reset Password
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestParam String username, 
                                           @RequestParam String otp,
                                           @RequestParam String newPassword) {
        try {
            // Re-verify OTP to be safe
            String cachedOtp = redisCacheService.getOtp(username);
            if (cachedOtp == null || !cachedOtp.equals(otp)) {
                return ResponseEntity.badRequest().body("Invalid request or OTP expired.");
            }

            // ✅ FIX: Switch to the correct database to update the password
            boolean userFound = setTenantContextForUser(username);

            if (!userFound) {
                return ResponseEntity.badRequest().body("Invalid request.");
            }

            User user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            // Update password
            user.setPassword(passwordEncoder.encode(newPassword));
            userRepository.save(user);

            // Clear OTP
            redisCacheService.deleteOtp(username);

            return ResponseEntity.ok().body("Password updated successfully.");

        } catch (Exception e) {
            logger.error("Failed to reset password for user: {}", username, e);
            return ResponseEntity.badRequest().body("An error occurred during password reset.");
        }
    }
}