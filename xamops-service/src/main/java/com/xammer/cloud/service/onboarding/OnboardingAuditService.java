package com.xammer.cloud.service.onboarding;

import com.xammer.cloud.domain.ClusterOnboardingEvent;
import com.xammer.cloud.repository.ClusterOnboardingEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Writes {@code cluster_onboarding_event} rows (design §2.3, §4.6).
 *
 * <p>Each write runs in its own {@link Propagation#REQUIRES_NEW} transaction so that a refusal
 * recorded while the main request transaction later rolls back still survives — e.g. a
 * {@code TOKEN_REFUSED} audited inside an exchange that then aborts.</p>
 *
 * <p>{@code detailJson} must never contain a token or key; callers pass only prefixes, ids, codes
 * and check results. A unit test asserts no stored detail matches a token/key shape.</p>
 */
@Service
public class OnboardingAuditService {

    private static final Logger logger = LoggerFactory.getLogger(OnboardingAuditService.class);

    // Actor types (design §2.1).
    public static final String ACTOR_USER = "USER";
    public static final String ACTOR_INSTALLER = "INSTALLER";
    public static final String ACTOR_AGENT = "AGENT";
    public static final String ACTOR_SYSTEM = "SYSTEM";

    private final ClusterOnboardingEventRepository eventRepository;
    private final Clock clock;

    public OnboardingAuditService(ClusterOnboardingEventRepository eventRepository, Clock clock) {
        this.eventRepository = eventRepository;
        this.clock = clock;
    }

    /**
     * Record one onboarding event in a fresh transaction. {@code detailJson} is the caller's
     * responsibility to keep secret-free (prefixes/ids/codes only).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long clientId, Long cloudAccountPk, String clusterName, Long clusterConfigId,
                       String eventType, String actorType, Long actorUserId, String detailJson) {
        ClusterOnboardingEvent event = new ClusterOnboardingEvent();
        event.setClientId(clientId);
        event.setCloudAccountPk(cloudAccountPk);
        event.setClusterName(clusterName);
        event.setClusterConfigId(clusterConfigId);
        event.setEventType(eventType);
        event.setActorType(actorType);
        event.setActorUserId(actorUserId);
        event.setDetailJson(detailJson);
        event.setCreatedAt(Instant.now(clock));
        eventRepository.save(event);
        logger.info("onboarding event {} actor={} account={} cluster={}",
                eventType, actorType, cloudAccountPk, clusterName);
    }
}
