package com.xammer.cloud.xampulse.service.stream;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Enforces the live-tail limits: 3 sessions per user and 10 per account. */
@Component("xpTailSessionRegistry")
public class TailSessionRegistry {

    public static final int MAX_PER_USER = 3;
    public static final int MAX_PER_ACCOUNT = 10;

    private record Entry(long userId, List<Long> accountIds) {}

    private final Map<String, Entry> sessions = new HashMap<>();

    /** @return false if registering would exceed a limit (nothing is recorded in that case) */
    public synchronized boolean tryRegister(String sessionId, long userId, List<Long> accountIds) {
        long forUser = sessions.values().stream().filter(e -> e.userId() == userId).count();
        if (forUser >= MAX_PER_USER) {
            return false;
        }
        for (Long a : accountIds) {
            long forAccount = sessions.values().stream().filter(e -> e.accountIds().contains(a)).count();
            if (forAccount >= MAX_PER_ACCOUNT) {
                return false;
            }
        }
        sessions.put(sessionId, new Entry(userId, List.copyOf(accountIds)));
        return true;
    }

    public synchronized void release(String sessionId) {
        sessions.remove(sessionId);
    }

    public synchronized int count() {
        return sessions.size();
    }
}
