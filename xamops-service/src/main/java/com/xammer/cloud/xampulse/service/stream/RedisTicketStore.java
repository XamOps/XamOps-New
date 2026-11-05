package com.xammer.cloud.xampulse.service.stream;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component("xpRedisTicketStore")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class RedisTicketStore implements TicketStore {

    private final StringRedisTemplate redis;

    public RedisTicketStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        redis.opsForValue().set(key, value, ttl);
    }

    @Override
    public Optional<String> getAndDelete(String key) {
        return Optional.ofNullable(redis.opsForValue().getAndDelete(key));
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(redis.opsForValue().get(key));
    }

    @Override
    public boolean putIfAbsent(String key, String value, Duration ttl) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, value, ttl));
    }
}
