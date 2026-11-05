package com.xammer.cloud.xampulse.service.setup;

import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * The ExternalId of the archive role. A different secret from the connection role's ExternalId, generated once per
 * account and stored encrypted (the entity column uses EncryptedStringConverter). Never returned by any API except
 * inside the quick-create link, and never logged.
 */
@Service("xpExternalIdService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class ExternalIdService {

    private final SecureRandom random = new SecureRandom();

    /** 32 random bytes, URL-safe; matches the template's allowed pattern and length. */
    public String generate() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return "xp-" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Returns the stored value, creating and setting one on the entity when absent. The caller saves the entity. */
    public String getOrCreate(XpAccountConfig config) {
        String existing = config.getArchiveExternalIdEnc();
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        String id = generate();
        config.setArchiveExternalIdEnc(id);
        return id;
    }
}
