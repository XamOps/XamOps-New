package com.xammer.cloud.xampulse.service.setup;

import com.xammer.cloud.xampulse.dto.XpAccountState;
import com.xammer.cloud.xampulse.exception.XamPulseException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.xammer.cloud.xampulse.dto.XpAccountState.*;

/** Allowed account state changes: PLANNED -> DEPLOYING -> VERIFYING -> CONNECTED / DEGRADED / DISCONNECTED. */
public final class SetupStateMachine {

    private static final Map<XpAccountState, Set<XpAccountState>> NEXT = new EnumMap<>(XpAccountState.class);

    static {
        NEXT.put(NOT_SET_UP, EnumSet.of(PLANNED));
        NEXT.put(PLANNED, EnumSet.of(PLANNED, DEPLOYING, VERIFYING, DISCONNECTED));
        NEXT.put(DEPLOYING, EnumSet.of(DEPLOYING, PLANNED, VERIFYING, DISCONNECTED));
        NEXT.put(VERIFYING, EnumSet.of(VERIFYING, DEPLOYING, CONNECTED, DEGRADED, DISCONNECTED));
        NEXT.put(CONNECTED, EnumSet.of(CONNECTED, DEGRADED, VERIFYING, DISCONNECTED));
        NEXT.put(DEGRADED, EnumSet.of(DEGRADED, CONNECTED, VERIFYING, DISCONNECTED));
        NEXT.put(DISCONNECTED, EnumSet.of(DISCONNECTED, PLANNED));
    }

    private SetupStateMachine() {}

    public static boolean canMove(XpAccountState from, XpAccountState to) {
        return NEXT.getOrDefault(from, Set.of()).contains(to);
    }

    public static XpAccountState move(XpAccountState from, XpAccountState to) {
        if (!canMove(from, to)) {
            throw XamPulseException.conflict("Cannot move XamPulse from " + from + " to " + to + ".");
        }
        return to;
    }

    public static XpAccountState parse(String s) {
        try {
            return s == null ? NOT_SET_UP : XpAccountState.valueOf(s);
        } catch (IllegalArgumentException e) {
            return NOT_SET_UP;
        }
    }
}
