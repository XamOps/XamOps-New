package com.xammer.billops.controller;

import com.xammer.billops.repository.AppUserRepository;
import com.xammer.billops.repository.UserRepository;
import com.xammer.cloud.domain.AppUser;
import com.xammer.cloud.domain.User;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/billops")
public class ImpersonationTokenController {

    @Value("${app.impersonation.secret-key}")
    private String secretKey;

    @Value("${demo.billops.email:demo.billops@xammer.in}")
    private String demoBillopsEmail;

    @Value("${demo.xamops.email:demo.xamops@xammer.in}")
    private String demoXamopsEmail;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private UserRepository userRepository;

    @PostMapping("/generate-impersonation-token")
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
    public ResponseEntity<Map<String, String>> generateToken(@RequestBody ImpersonationTokenRequest request) {
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secretKey));

        String token = Jwts.builder()
                .setSubject(String.valueOf(request.getUserId()))
                .claim("tenantId", request.getTenantId())
                .claim("allowedAccountIds", request.getAllowedAccountIds())
                .claim("role", "ROLE_XAMOPS")
                .setIssuer("billops")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 8L * 60 * 60 * 1000))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();

        return ResponseEntity.ok(Map.of("token", token));
    }

    /**
     * Generates an impersonation token for a demo account (billops or xamops).
     * Only callable by ROLE_BILLOPS_ADMIN. The demo users are fixed system accounts.
     */
    @PostMapping("/demo-token")
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
    public ResponseEntity<?> generateDemoToken(@RequestBody Map<String, String> body) {
        String type = body.get("type"); // "billops" or "xamops"
        if (type == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "type is required"));
        }

        if ("billops".equals(type)) {
            // Demo billops user — lives in AppUser (billops tenant)
            Optional<AppUser> userOpt = appUserRepository.findByEmail(demoBillopsEmail);
            if (userOpt.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("message", "Demo billops user not found"));
            }
            AppUser user = userOpt.get();
            String tenantId = user.getClient() != null ? user.getClient().getName() : null;
            String token = buildToken(user.getId(), tenantId, null, "ROLE_BILLOPS_ADMIN");
            return ResponseEntity.ok(Map.of("token", token, "destination", "billops-admin-dashboard"));
        }

        if ("xamops".equals(type)) {
            // Demo xamops user — lives in User (xamops tenant)
            Optional<User> xamopsUserOpt = userRepository.findByEmail(demoXamopsEmail);
            if (xamopsUserOpt.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("message", "Demo xamops user not found"));
            }
            User xamopsUser = xamopsUserOpt.get();
            String tenantId = xamopsUser.getClient() != null ? xamopsUser.getClient().getName() : null;
            String token = buildToken(xamopsUser.getId(), tenantId, null, "ROLE_XAMOPS");
            return ResponseEntity.ok(Map.of("token", token, "destination", "unified-dashboard"));
        }

        return ResponseEntity.badRequest().body(Map.of("message", "type must be 'billops' or 'xamops'"));
    }

    private String buildToken(Long userId, String tenantId, String allowedAccountIds, String role) {
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secretKey));
        var builder = Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim("role", role)
                .setIssuer("billops")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 8L * 60 * 60 * 1000));
        if (tenantId != null) builder.claim("tenantId", tenantId);
        if (allowedAccountIds != null) builder.claim("allowedAccountIds", allowedAccountIds);
        return builder.signWith(key, SignatureAlgorithm.HS256).compact();
    }

    public static class ImpersonationTokenRequest {
        private long userId;
        private String tenantId;
        private String allowedAccountIds;

        public long getUserId() { return userId; }
        public void setUserId(long userId) { this.userId = userId; }
        public String getTenantId() { return tenantId; }
        public void setTenantId(String tenantId) { this.tenantId = tenantId; }
        public String getAllowedAccountIds() { return allowedAccountIds; }
        public void setAllowedAccountIds(String allowedAccountIds) { this.allowedAccountIds = allowedAccountIds; }
    }
}
