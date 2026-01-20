package com.xammer.billops.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class RedisListenerConfig {

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(redisTaskExecutor());
        return container;
    }

    @Bean(name = "redisTaskExecutor")
    public Executor redisTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5); // Start with 5 threads
        executor.setMaxPoolSize(10); // Max 10 threads
        executor.setQueueCapacity(1000); // Buffer up to 1000 tasks
        executor.setThreadNamePrefix("RedisListener-");
        executor.initialize();
        return executor;
    }
}
