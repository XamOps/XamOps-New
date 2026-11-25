package com.xammer.cloud.xampulse.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Fails startup when XamPulse demo mode is switched on under a production-like profile, or when none of the active
 * profiles is in {@code xampulse.demo.allowed-profiles} (default {@code local,dev}), so generated sample data can
 * never be mistaken for a customer's real telemetry. Mirrors {@code MockDataGuard}. The allow-list is enforced: demo
 * needs an explicitly allowed profile, not merely the absence of a production-like one. Server-side demo seeding is
 * deliberately not built (see implementation-status.md); this guard exists so the flag can never be silently
 * enabled outside local and dev.
 */
@Component("xpDemoGuard")
public class XamPulseDemoGuard {

    private static final Logger log = LoggerFactory.getLogger(XamPulseDemoGuard.class);
    static final List<String> PRODUCTION_LIKE = List.of("prod", "production", "uat", "preprod", "sit");

    private final Environment environment;
    private final XamPulseProperties props;

    public XamPulseDemoGuard(Environment environment, XamPulseProperties props) {
        this.environment = environment;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    void verify() {
        List<String> active = Arrays.asList(environment.getActiveProfiles());
        String offending = offendingProfile(props.getDemo().isEnabled(), active);
        if (offending == null && !allowed(props.getDemo().isEnabled(), active, props.getDemo().getAllowedProfiles())) {
            throw new IllegalStateException("xampulse.demo.enabled=true is set but none of the active profiles "
                    + Arrays.toString(environment.getActiveProfiles()) + " is in xampulse.demo.allowed-profiles "
                    + props.getDemo().getAllowedProfiles() + ". Demo mode serves generated sample data and is only "
                    + "allowed on those profiles. Unset XAMPULSE_DEMO_ENABLED.");
        }
        if (offending != null) {
            throw new IllegalStateException("xampulse.demo.enabled=true is set while the '" + offending
                    + "' profile is active. Demo mode serves generated sample data instead of real telemetry and must "
                    + "never run in a production-like environment. Unset XAMPULSE_DEMO_ENABLED.");
        }
        if (props.getDemo().isEnabled()) {
            log.warn("XAMPULSE DEMO MODE FLAG IS ON (profiles {}). Any demo data is generated sample data, not from a customer account.",
                    Arrays.toString(environment.getActiveProfiles()));
        }
    }

    /** @return true when demo is off, or at least one active profile is in the allow-list (case-insensitive) */
    static boolean allowed(boolean demoEnabled, List<String> activeProfiles, List<String> allowedProfiles) {
        if (!demoEnabled) {
            return true;
        }
        if (allowedProfiles == null) {
            return false;
        }
        return activeProfiles.stream().anyMatch(p -> allowedProfiles.stream().anyMatch(a -> a.equalsIgnoreCase(p)));
    }

    /** @return the first production-like active profile if demo is enabled, else null */
    static String offendingProfile(boolean demoEnabled, List<String> activeProfiles) {
        if (!demoEnabled) {
            return null;
        }
        return activeProfiles.stream().filter(p -> PRODUCTION_LIKE.contains(p.toLowerCase(Locale.ROOT))).findFirst().orElse(null);
    }
}
