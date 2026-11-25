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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Derives EC2_STATE timeline events ("i-0abc is stopped") from the inventory projection's current EC2 instance states.
 * An event means a change: an instance whose state differs from the last state seen, or an instance that was not
 * there before. The first pass for an account after start-up only records the baseline, because what came before is
 * unknown (a change made while the service was down is not shown). Only instances (i-...) count: the inventory also
 * holds key pairs, volumes and other EC2 resources, which have no running state. All errors are swallowed.
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
    private static final java.util.regex.Pattern INSTANCE = java.util.regex.Pattern.compile("^i-[0-9a-f]{8,17}$");
    /** tenant -> (instance -> last state seen). A tenant without an entry has no baseline yet. */
    private final Map<Long, Map<String, String>> lastState = new ConcurrentHashMap<>();

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
        private static final java.util.regex.Pattern INSTANCE_ID = java.util.regex.Pattern.compile("^i-[0-9a-f]{8,17}$");
        private final InventoryProjectionService inventory;

        public InventoryEc2StateSource(InventoryProjectionService inventory) {
            this.inventory = inventory;
        }

        @Override
        public List<Ec2Instance> currentStates(long tenantId) {
            // tenant_id in XamPulse is the cloud_account primary key the inventory projection also keys on.
            return inventory.flatList(tenantId).stream()
                    .filter(r -> r.getType() != null && r.getType().toUpperCase().contains("EC2"))
                    .filter(r -> r.getId() != null && INSTANCE_ID.matcher(r.getId()).matches())
                    .filter(r -> r.getState() != null && !r.getState().isBlank())
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
        Map<String, String> current = new java.util.HashMap<>();
        for (Ec2Instance inst : source.currentStates(tenantId)) {
            if (inst.instanceId() != null && INSTANCE.matcher(inst.instanceId()).matches() && inst.state() != null && !inst.state().isBlank()) {
                current.put(inst.instanceId(), inst.state().trim());
            }
        }
        if (current.isEmpty()) {
            return; // an empty read (inventory not loaded yet, a sync glitch) says nothing about changes
        }
        Map<String, String> before = lastState.get(tenantId);
        if (before == null) {
            lastState.put(tenantId, new ConcurrentHashMap<>(current)); // baseline: nothing is known to have changed
            return;
        }
        for (Map.Entry<String, String> e : current.entrySet()) {
            String was = before.get(e.getKey());
            if (e.getValue().equalsIgnoreCase(was == null ? "" : was)) {
                continue;
            }
            String title = "EC2 " + e.getKey() + " is " + e.getValue();
            String detail = was == null ? "new instance" : "was " + was;
            try {
                eventWriter.record(tenantId, "EC2_STATE", e.getKey(), title, detail, now);
                before.put(e.getKey(), e.getValue());
            } catch (Exception ex) {
                log.warn("XamPulse EC2 state event write failed for account {}: {}", tenantId, ex.getClass().getSimpleName());
            }
        }
        before.keySet().retainAll(current.keySet()); // a terminated instance that left the inventory is forgotten
    }

    private static boolean isActive(XpAccountConfig cfg) {
        XpAccountState state = SetupStateMachine.parse(cfg.getState());
        return state == XpAccountState.CONNECTED || state == XpAccountState.DEGRADED;
    }
}
