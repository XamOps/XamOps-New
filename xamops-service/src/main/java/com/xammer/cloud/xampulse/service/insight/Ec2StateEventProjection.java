package com.xammer.cloud.xampulse.service.insight;

import com.xammer.cloud.config.SchedulingEnvironmentConfig;
import com.xammer.cloud.service.inventory.InventoryProjectionService;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.dto.XpAccountState;
import com.xammer.cloud.xampulse.repository.XpAccountConfigRepository;
import com.xammer.cloud.xampulse.service.setup.SetupStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Derives EC2_STATE timeline events from the inventory projection's current EC2 instance state. Minimal by design
 * (see FEAT-001): it is the secondary deliverable behind the ALERT/ANOMALY path. It reads the current state per
 * instance for CONNECTED/DEGRADED accounts and records an EC2_STATE event the first time a (resource, state) pair is
 * seen in a given UTC hour, deduped in memory. All errors are swallowed.
 *
 * <p>The source is behind a tiny {@link Ec2StateSource} seam so it can be faked in tests without standing up the
 * inventory projection's repositories and ObjectMapper.
 */
@Component("xpEc2StateEventProjection")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class Ec2StateEventProjection {

    private static final Logger log = LoggerFactory.getLogger(Ec2StateEventProjection.class);

    /** Current state of each EC2 instance in a tenant's account. */
    public interface Ec2StateSource {
        /** (instanceId, state) for every EC2 instance currently projected for this tenant. */
        List<Ec2Instance> currentStates(long tenantId);
    }

    public record Ec2Instance(String instanceId, String state) {}

    private final XpAccountConfigRepository configs;
    private final Ec2StateSource source;
    private final EventWriter eventWriter;
    private final SchedulingEnvironmentConfig scheduling;
    /** key -> last UTC hour recorded, so the same (resource,state) only writes once per hour. */
    private final Map<String, Long> lastSeenHour = new ConcurrentHashMap<>();

    public Ec2StateEventProjection(XpAccountConfigRepository configs, Ec2StateSource source,
                                   EventWriter eventWriter, SchedulingEnvironmentConfig scheduling) {
        this.configs = configs;
        this.source = source;
        this.eventWriter = eventWriter;
        this.scheduling = scheduling;
    }

    /** Spring wiring: wrap the inventory projection. Only present when xampulse is enabled. */
    @Component("xpInventoryEc2StateSource")
    @ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
    public static class InventoryEc2StateSource implements Ec2StateSource {
        private final InventoryProjectionService inventory;

        public InventoryEc2StateSource(InventoryProjectionService inventory) {
            this.inventory = inventory;
        }

        @Override
        public List<Ec2Instance> currentStates(long tenantId) {
            // tenant_id in XamPulse is the cloud_account primary key the inventory projection also keys on.
            return inventory.flatList(tenantId).stream()
                    .filter(r -> r.getType() != null && r.getType().toUpperCase().contains("EC2"))
                    .filter(r -> r.getId() != null && r.getState() != null && !r.getState().isBlank())
                    .map(r -> new Ec2Instance(r.getId(), r.getState()))
                    .toList();
        }
    }

    @Scheduled(cron = "${xampulse.baseline.cron}")
    public void runAll() {
        if (!scheduling.isSchedulingEnabled()) {
            return;
        }
        Instant now = Instant.now();
        for (XpAccountConfig cfg : configs.findAll()) {
            if (cfg.getCloudAccountId() == null || !isActive(cfg)) {
                continue;
            }
            try {
                runForAccount(cfg.getCloudAccountId(), now);
            } catch (RuntimeException e) {
                log.warn("XamPulse EC2 state projection failed for account {}: {}", cfg.getCloudAccountId(), e.getClass().getSimpleName());
            }
        }
    }

    void runForAccount(long tenantId, Instant now) {
        long hour = now.atZone(ZoneOffset.UTC).toEpochSecond() / 3600L;
        for (Ec2Instance inst : source.currentStates(tenantId)) {
            String key = tenantId + "|" + inst.instanceId() + "|" + inst.state();
            Long seen = lastSeenHour.get(key);
            if (seen != null && seen == hour) {
                continue;
            }
            try {
                eventWriter.record(tenantId, "EC2_STATE", inst.instanceId(),
                        "EC2 " + inst.instanceId() + " is " + inst.state(), inst.state(), now);
                lastSeenHour.put(key, hour);
            } catch (Exception e) {
                log.warn("XamPulse EC2 state event write failed for account {}: {}", tenantId, e.getClass().getSimpleName());
            }
        }
    }

    private static boolean isActive(XpAccountConfig cfg) {
        XpAccountState state = SetupStateMachine.parse(cfg.getState());
        return state == XpAccountState.CONNECTED || state == XpAccountState.DEGRADED;
    }
}
