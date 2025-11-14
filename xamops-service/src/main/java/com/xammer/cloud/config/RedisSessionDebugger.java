package com.xammer.cloud.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class RedisSessionDebugger {
    
    private final ApplicationContext context;
    
    public RedisSessionDebugger(ApplicationContext context) {
        this.context = context;
    }
    
    @EventListener(ApplicationReadyEvent.class)
    public void debugRedisSession() {
        log.warn("========== REDIS SESSION DEBUG INFO ==========");
        
        try {
            RedisSerializer<?> serializer = context.getBean("springSessionDefaultRedisSerializer", RedisSerializer.class);
            log.warn("✓ springSessionDefaultRedisSerializer bean found: {}", serializer.getClass().getName());
        } catch (Exception e) {
            log.error("✗ springSessionDefaultRedisSerializer bean NOT FOUND!", e);
        }
        
        String[] beanNames = context.getBeanNamesForType(RedisSerializer.class);
        log.warn("All RedisSerializer beans: {}", String.join(", ", beanNames));
    }
}
