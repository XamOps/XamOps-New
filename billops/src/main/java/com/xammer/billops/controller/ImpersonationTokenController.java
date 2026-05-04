package com.xammer.billops.controller;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
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

@RestController
@RequestMapping("/api/billops")
public class ImpersonationTokenController {

    @Value("${app.impersonation.secret-key}")
    private String secretKey;

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
