package com.xammer.cloud.service.autospotting.core.list;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.xammer.cloud.dto.autospotting.v2.GroupListDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * A short server-side cache of a complete group list per account (design-ux-ops.md §4.6, AC-OBS-05).
 *
 * <ul>
 *   <li>Only a <strong>complete</strong> list is stored: a partial response is never served as complete.</li>
 *   <li>A refresh ({@code Cache-Control: no-cache}) bypasses it.</li>
 *   <li>A <strong>terminal operation evicts it</strong> ({@link #evict}), so a toggle, an enable or a
 *       revert shows its result on the next read, never a stale row
 *       ({@code AutoSpottingListCacheTest#terminalOperationEvictsList}, design-ux-ops.md §9.8).</li>
 * </ul>
 */
@Component
public class GroupListCache {

    private final Cache<Long, GroupListDto> cache;

    public GroupListCache(@Value("${autospotting.list.cache-ttl:60s}") Duration ttl) {
        this.cache = Caffeine.newBuilder().maximumSize(5_000).expireAfterWrite(ttl).build();
    }

    /** The cached complete list for an account, if fresh. */
    public Optional<GroupListDto> get(Long cloudAccountId) {
        return Optional.ofNullable(cache.getIfPresent(cloudAccountId));
    }

    /** Store a list. A partial list is ignored. */
    public void put(Long cloudAccountId, GroupListDto list) {
        if (list != null && !list.partial()) {
            cache.put(cloudAccountId, list);
        }
    }

    /** Drop the account's cached list (a terminal operation, a toggle). */
    public void evict(Long cloudAccountId) {
        if (cloudAccountId != null) {
            cache.invalidate(cloudAccountId);
        }
    }
}
