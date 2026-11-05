package com.xammer.cloud.xampulse.service.stream;

import java.util.List;

/** Re-checks, while a live tail is open, that the user may still read the accounts the ticket was issued for. */
public interface TailAccessRevalidator {

    enum Verdict { ALLOWED, DENIED, UNKNOWN }

    /**
     * @param effectiveUserId the user whose data is shown (the impersonated user when {@code actorUserId} is set)
     * @param actorUserId     the real admin when impersonating, else null
     */
    Verdict check(long effectiveUserId, Long actorUserId, List<Long> accountIds);
}
