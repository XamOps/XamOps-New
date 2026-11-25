package com.xammer.cloud.xampulse.service.archive;

import com.xammer.cloud.xampulse.domain.XpAccountConfig;

import java.nio.file.Path;
import java.util.Optional;

/** The customer's bucket as seen through the archive role. Every call assumes the role fresh from the cached provider. */
public interface ArchiveStore {

    /** Uploads with SSE-KMS (and the customer key when one is configured). Idempotent for a given key. */
    void putFile(XpAccountConfig cfg, String key, Path file);

    void putText(XpAccountConfig cfg, String key, String text);

    /** Empty when the key does not exist. */
    Optional<String> getText(XpAccountConfig cfg, String key);
}
