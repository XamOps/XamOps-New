package com.xammer.cloud.service.onboarding;

import org.springframework.stereotype.Service;

/**
 * Entitlement hook for onboarding features (design §4.6, OQ-1). v1 returns {@code true} for every
 * (client, feature) pair; the tiering decision is deferred and lands here without a rebuild.
 */
@Service
public class OnboardingEntitlementService {

    /** v1: everything is entitled (OQ-1 placeholder). */
    public boolean entitled(Long clientId, String featureId) {
        return true;
    }
}
