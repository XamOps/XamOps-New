package com.xammer.billops.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

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
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("RedisListener-");
        executor.initialize();
        return executor;
    }

    /**
     * Intercepts the Spring Session Redis message listener container (auto-configured
     * by Spring Boot) before it starts and replaces its default SimpleAsyncTaskExecutor
     * with a bounded thread pool. Without this, SimpleAsyncTaskExecutor spawns a new
     * OS thread per Redis keyspace notification, eventually exhausting pthread limits.
     */
    @Bean
    public BeanPostProcessor springSessionRedisListenerExecutorPatcher() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
                if ("springSessionRedisMessageListenerContainer".equals(beanName)
                        && bean instanceof RedisMessageListenerContainer) {
                    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
                    executor.setCorePoolSize(4);
                    executor.setMaxPoolSize(8);
                    executor.setQueueCapacity(500);
                    executor.setThreadNamePrefix("spring-session-redis-");
                    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
                    executor.initialize();
                    ((RedisMessageListenerContainer) bean).setTaskExecutor(executor);
                }
                return bean;
            }
        };
    }
}
