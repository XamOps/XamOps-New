package com.xammer.cloud.xampulse.service.archive;

import com.xammer.cloud.xampulse.domain.XpAccountConfig;

/**
 * Called once per archive failure streak, when the consecutive-failure count first reaches the threshold. Implementations
 * must never throw: an alert failure must not mask the archive failure.
 */
public interface ArchiveFailureAlerter {

    /** @param errorClass simple class name of the failure (never a message) */
    void archiveFailing(XpAccountConfig cfg, int failures, String errorClass);
}
