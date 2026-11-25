package com.xammer.cloud.xampulse.service.setup;

import com.xammer.cloud.xampulse.domain.XpAccountConfig;

/** Proves XamOps can use the customer's archive role: assume it with the ExternalId, then write one small object. */
public interface ArchiveAccessVerifier {

    /** assumable=false means the role could not be assumed; writable=false means the put failed (policy, KMS, wrong bucket). */
    record Result(boolean assumable, boolean writable, String detail) {}

    Result verify(XpAccountConfig config);
}
