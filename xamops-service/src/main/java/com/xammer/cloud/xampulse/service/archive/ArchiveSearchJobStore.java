package com.xammer.cloud.xampulse.service.archive;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.xampulse.dto.XpArchiveSearchJob;
import com.xammer.cloud.xampulse.service.stream.TicketStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Archive search jobs in the shared ticket store (Redis in production), so any node can answer a poll. Ids are 128
 * random bits. A record remembers its owner and account; callers compare those before returning anything. Terminal
 * states are sticky for CANCELLED: a runner finishing late never overwrites a cancel.
 */
@Component("xpArchiveSearchJobStore")
@ConditionalOnProperty(name = {"xampulse.enabled", "xampulse.archive.enabled"}, havingValue = "true")
public class ArchiveSearchJobStore {

    public static final Duration TTL = Duration.ofHours(1);
    static final Duration LOCK_TTL = Duration.ofMinutes(10);
    private static final String PREFIX = "xp:arch:";
    private static final String LOCK_PREFIX = "xp:arch:active:";

    public record Record(long ownerUserId, long accountId, XpArchiveSearchJob job) {}

    private final TicketStore store;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    public ArchiveSearchJobStore(TicketStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.mapper = objectMapper;
    }

    public String newId() {
        byte[] raw = new byte[16];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    public void save(Record r) {
        try {
            store.put(PREFIX + r.job().id(), mapper.writeValueAsString(r), TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not store archive job");
        }
    }

    public Optional<Record> find(String id) {
        if (id == null || !id.matches("^[A-Za-z0-9_-]{22}$")) {
            return Optional.empty();
        }
        return store.get(PREFIX + id).flatMap(json -> {
            try {
                return Optional.of(mapper.readValue(json, Record.class));
            } catch (JsonProcessingException e) {
                return Optional.empty();
            }
        });
    }

    /** Read-modify-write; a CANCELLED job is left alone unless the change keeps it CANCELLED. */
    public void update(String id, UnaryOperator<XpArchiveSearchJob> change) {
        find(id).ifPresent(r -> {
            XpArchiveSearchJob next = change.apply(r.job());
            if ("CANCELLED".equals(r.job().state()) && !"CANCELLED".equals(next.state())) {
                return;
            }
            save(new Record(r.ownerUserId(), r.accountId(), next));
        });
    }

    public boolean isCancelled(String id) {
        return find(id).map(r -> "CANCELLED".equals(r.job().state())).orElse(true);
    }

    public void delete(String id) {
        store.getAndDelete(PREFIX + id);
    }

    /** At most one running job per account. */
    public boolean tryLock(long accountId, String jobId) {
        return store.putIfAbsent(LOCK_PREFIX + accountId, jobId, LOCK_TTL);
    }

    public void unlock(long accountId) {
        store.getAndDelete(LOCK_PREFIX + accountId);
    }
}
