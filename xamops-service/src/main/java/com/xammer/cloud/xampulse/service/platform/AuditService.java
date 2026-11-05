package com.xammer.cloud.xampulse.service.platform;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.domain.XpAudit;
import com.xammer.cloud.xampulse.repository.XpAuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Writes the XamPulse audit trail (xp_audit). Never stores raw query text or log content: only a short digest and
 * length for searches, so the trail is useful for review without becoming a second copy of customer data.
 */
@Service("xpAuditService")
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final XpAuditRepository repo;

    public AuditService(XpAuditRepository repo) {
        this.repo = repo;
    }

    public void record(ClientUserDetails user, Long accountId, String action, String target, String ip) {
        recordForUser(user == null ? null : user.getId(), accountId, action, target, ip);
    }

    /** For callers without a security principal (WebSocket threads); the user id comes from a validated ticket. */
    public void recordForUser(Long userId, Long accountId, String action, String target, String ip) {
        try {
            XpAudit a = new XpAudit();
            a.setTs(Instant.now());
            a.setUserId(userId == null ? 0L : userId);
            a.setCloudAccountId(accountId);
            a.setAction(action);
            a.setTarget(target == null ? null : (target.length() > 500 ? target.substring(0, 500) : target));
            a.setIp(ip);
            repo.save(a);
        } catch (RuntimeException e) {
            // Auditing must not take the feature down, but the failure must be visible.
            log.error("Failed to write XamPulse audit entry action={} account={}", action, accountId, e);
        }
    }

    /** "q:len=12,sha=ab12cd34" */
    public static String describeQuery(String query) {
        String q = query == null ? "" : query;
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(q.getBytes(StandardCharsets.UTF_8));
            return "q:len=" + q.length() + ",sha=" + HexFormat.of().formatHex(d, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            return "q:len=" + q.length();
        }
    }
}
