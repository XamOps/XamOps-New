package com.xammer.cloud.autospotting.core;

import com.xammer.cloud.service.autospotting.core.EligibilityCache;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** design-core.md §9.2: only successes are cached (24 h), keyed by account, group, check and live fingerprint; no Redis is a miss. */
@SuppressWarnings("unchecked")
class EligibilityCacheTest {

    private final StringRedisTemplate template = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);

    EligibilityCacheTest() {
        when(template.opsForValue()).thenReturn(ops);
    }

    @Test
    void theKeyCarriesAccountGroupCheckAndFingerprint() {
        String a = EligibilityCache.key(7L, "ap-south-1/web", "PERMISSION_MISSING", "fp1");
        assertThat(a).contains("7").contains("ap-south-1/web").contains("PERMISSION_MISSING").endsWith("fp1");
        assertThat(EligibilityCache.key(7L, "ap-south-1/web", "PERMISSION_MISSING", "fp2")).isNotEqualTo(a);
        assertThat(EligibilityCache.key(8L, "ap-south-1/web", "PERMISSION_MISSING", "fp1")).isNotEqualTo(a);
    }

    @Test
    void aRecordedPassIsReadBackAndWrittenForTwentyFourHours() {
        when(provider.getIfAvailable()).thenReturn(template);
        EligibilityCache cache = new EligibilityCache(provider);
        cache.recordPass(7L, "g", "C", "fp");
        verify(ops).set(anyString(), anyString(), eq(Duration.ofHours(24)));
        when(ops.get(EligibilityCache.key(7L, "g", "C", "fp"))).thenReturn("PASS");
        assertThat(cache.isPassCached(7L, "g", "C", "fp")).isTrue();
        assertThat(cache.isPassCached(7L, "g", "C", "other-fp")).isFalse();
    }

    @Test
    void noRedisIsAMissAndAWriteIsANoOp() {
        when(provider.getIfAvailable()).thenReturn(null);
        EligibilityCache cache = new EligibilityCache(provider);
        assertThat(cache.isPassCached(7L, "g", "C", "fp")).isFalse();
        cache.recordPass(7L, "g", "C", "fp");
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
        assertThat(cache.peek(7L, "g", "C", "fp")).isEmpty();
    }

    @Test
    void aRedisFaultDegradesToAMissAndNeverThrows() {
        when(provider.getIfAvailable()).thenReturn(template);
        when(ops.get(anyString())).thenThrow(new RuntimeException("redis down"));
        org.mockito.Mockito.doThrow(new RuntimeException("redis down")).when(ops).set(anyString(), anyString(), any(Duration.class));
        EligibilityCache cache = new EligibilityCache(provider);
        assertThat(cache.isPassCached(7L, "g", "C", "fp")).isFalse();
        cache.recordPass(7L, "g", "C", "fp");
        assertThat(cache.peek(7L, "g", "C", "fp")).isEmpty();
    }
}
