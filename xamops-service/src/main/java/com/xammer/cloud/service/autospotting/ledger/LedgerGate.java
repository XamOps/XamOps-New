package com.xammer.cloud.service.autospotting.ledger;

import com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties;
import com.xammer.cloud.service.autospotting.core.switches.SwitchService;
import com.xammer.cloud.service.autospotting.core.switches.SwitchState;
import org.springframework.stereotype.Component;

/**
 * Switch L for the ledger jobs (design-core.md §6.1 row L, design-ledger.md §13.1). The ledger jobs
 * ({@code ledger-observe}, {@code price-snapshot}, {@code ledger-writer}) are governed only by this
 * switch and {@code scheduling.enabled}, never by switches (a) to (e), so a freeze or a paused
 * conversion does not stop measurement.
 *
 * <p>The effective value is {@code property AND NOT db.engaged}: the property
 * {@code autospotting.ledger.<cloud>.writer-enabled} (false by default, true in prod only) and the
 * tighten-only DB row {@code LEDGER:<cloud>}. An unreadable switch store counts as off: the
 * ledger would rather skip a run (the next trigger writes it) than write while it cannot tell whether
 * an operator switched it off.
 */
@Component
public class LedgerGate {

    private final AutoSpottingSwitchProperties properties;
    private final SwitchService switches;

    public LedgerGate(AutoSpottingSwitchProperties properties, SwitchService switches) {
        this.properties = properties;
        this.switches = switches;
    }

    /** The DB scope key of the tighten-only ledger writer switch for a cloud. */
    public static String writerKey(boolean aws) {
        return "LEDGER:" + (aws ? "aws" : "gcp");
    }

    /** True when the AWS ledger jobs may run now. */
    public boolean awsWriterOn() {
        if (!properties.ledgerWriterEnabled(true)) {
            return false;
        }
        SwitchState state = switches.current();
        return !state.switchStoreUnreadable() && !state.engagedScopeKeys().contains(writerKey(true));
    }
}
