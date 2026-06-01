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

import java.util.Optional;
import java.util.Random;

@RestController
@RequestMapping("/api/auth")
public class PasswordResetController {

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
    private void setTenantContextForUser(String username) {
        // 1. Global Lookup
        Optional<GlobalUserDto> globalUserOpt = masterDatabaseService.findGlobalUser(username);
        
        if (globalUserOpt.isPresent()) {
            String tenantId = globalUserOpt.get().getTenantId();
            // 2. Context Switch
            TenantContext.setCurrentTenant(tenantId);
        } else {
            throw new RuntimeException("User not found in Global Directory");
        }
    }

    // 1. Send OTP
    @PostMapping("/forgot-password")
    public ResponseEntity<?> sendOtp(@RequestParam String username, @RequestParam String email) {
        try {
            // ✅ FIX: Switch to the correct database for this user
            setTenantContextForUser(username);

            // Find user in their specific Tenant DB
            User user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            // Verify email matches
            if (user.getEmail() == null || !user.getEmail().equalsIgnoreCase(email)) {
                return ResponseEntity.badRequest().body("Email does not match our records.");
            }

            // Generate 6-digit OTP
            String otp = String.format("%06d", new Random().nextInt(999999));
            
            // Save OTP to Redis (Expires in 5 minutes)
            redisCacheService.saveOtp(username, otp);

            // Send Email
            String subject = "BillOps Password Reset Code";
            String body = "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1.0'><title>Password Reset Code</title></head>"
                          + "<body style='margin:0;padding:0;background:#f1f5f9;font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,Arial,sans-serif;'>"
                          + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0'><tr><td style='padding:32px 16px;'>"
                          + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' style='max-width:680px;margin:0 auto;background:#ffffff;border-radius:16px;overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,0.08);border:1px solid #e2e8f0;'>"
                          + "<tr><td style='background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);padding:40px;'>"
                          + "<div style='margin-bottom:16px;'><span style='display:inline-block;background:rgba(255,255,255,0.2);padding:6px 14px;border-radius:20px;color:#fff;font-size:12px;font-weight:700;letter-spacing:1.5px;text-transform:uppercase;'>XamOps</span></div>"
                          + "<div style='font-size:40px;margin-bottom:14px;'>&#128272;</div>"
                          + "<h1 style='margin:0 0 6px;color:#fff;font-size:24px;font-weight:700;'>Password Reset Code</h1>"
                          + "<p style='margin:0;color:rgba(255,255,255,0.85);font-size:15px;'>XamOps account security</p>"
                          + "</td></tr>"
                          + "<tr><td style='padding:32px 40px;'>"
                          + "<p style='margin:0 0 20px;color:#374151;font-size:15px;'>Use the code below to reset your password. It expires in <strong>5 minutes</strong>.</p>"
                          + "<div style='background:#f1f5f9;border-radius:12px;padding:24px;text-align:center;margin-bottom:20px;letter-spacing:0.4em;font-size:36px;font-weight:700;color:#1e293b;font-family:monospace;'>" + otp + "</div>"
                          + "<p style='margin:0;color:#64748b;font-size:13px;'>If you did not request a password reset, you can safely ignore this email.</p>"
                          + "</td></tr>"
                          + "<tr><td style='background:#f8fafc;border-top:1px solid #e2e8f0;padding:24px 40px;text-align:center;'>"
                          + "<span style='display:inline-block;background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);padding:7px 18px;border-radius:6px;color:#fff;font-size:13px;font-weight:700;letter-spacing:1px;'>XAMOPS</span>"
                          + "<p style='margin:12px 0 4px;color:#64748b;font-size:12px;'>Cloud Cost Management &amp; Security Platform</p>"
                          + "<p style='margin:0;color:#cbd5e1;font-size:11px;'>&copy; 2026 XamOps. All rights reserved.</p>"
                          + "</td></tr>"
                          + "</table></td></tr></table></body></html>";
            
            // Log for debugging (in case email fails)
            System.out.println(">>> OTP for " + username + ": " + otp);
            
            emailService.sendHtmlEmail(email, subject, body);

            return ResponseEntity.ok().body("OTP sent to your email.");

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body("Error: " + e.getMessage());
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
            setTenantContextForUser(username);

            User user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            // Update password
            user.setPassword(passwordEncoder.encode(newPassword));
            userRepository.save(user);

            // Clear OTP
            redisCacheService.deleteOtp(username);

            return ResponseEntity.ok().body("Password updated successfully.");

        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Failed to reset password: " + e.getMessage());
        }
    }
}