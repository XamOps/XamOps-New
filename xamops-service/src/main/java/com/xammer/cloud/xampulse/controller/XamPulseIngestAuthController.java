package com.xammer.cloud.xampulse.controller;

import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.service.agent.IngestAuthorizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Target of nginx {@code auth_request} for {@code /xampulse-ingest/}. Not for browsers or agents: it answers 404
 * unless the shared secret header (added by nginx, from a file outside the repo) matches, so it is invisible to
 * anyone who reaches it through the public edge. Over-quota tenants get 429 + Retry-After (403 in nginx mode).
 */
@RestController("xpIngestAuthController")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class XamPulseIngestAuthController {

    private final XamPulseProperties props;
    private final IngestAuthorizer authorizer;

    public XamPulseIngestAuthController(XamPulseProperties props, IngestAuthorizer authorizer) {
        this.props = props;
        this.authorizer = authorizer;
    }

    @GetMapping("/internal/xampulse/auth")
    public ResponseEntity<Void> auth(@RequestHeader(value = "X-Xp-Internal", required = false) String secret,
                                     @RequestHeader(value = "X-Xp-Token", required = false) String token,
                                     @RequestHeader(value = "X-Xp-Auth-Mode", required = false) String mode) {
        if (!secretMatches(props.getInternalSecret(), secret)) {
            return ResponseEntity.notFound().build();
        }
        IngestAuthorizer.Decision d = authorizer.authorize(token);
        if (d.status() == IngestAuthorizer.Status.QUOTA_EXCEEDED) {
            // Called directly: a plain 429 with Retry-After. Behind nginx auth_request any status other than 2xx, 401
            // and 403 becomes a 500, so nginx mode answers 403 plus the same advisory headers and the edge maps that
            // 403 to the real 429 (see api-gateway/nginx-prod.conf, @xp_quota).
            boolean viaNginx = "nginx".equalsIgnoreCase(mode);
            return ResponseEntity.status(viaNginx ? 403 : 429)
                    .header("Retry-After", String.valueOf(d.retryAfterSec()))
                    .header("X-Xp-Quota", "exceeded")
                    .build();
        }
        if (d.status() != IngestAuthorizer.Status.OK) {
            return ResponseEntity.status(401).build();
        }
        HttpHeaders h = new HttpHeaders();
        h.add("X-Xp-Tenant", String.valueOf(d.tenantId()));
        h.add("X-Xp-Instance", d.instanceId());
        h.add("X-Xp-Retain-Days", String.valueOf(d.retainDays()));
        return ResponseEntity.ok().headers(h).build();
    }

    /** Constant-time comparison; an unset server secret never matches. */
    static boolean secretMatches(String expected, String provided) {
        if (expected == null || expected.isBlank() || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }
}
