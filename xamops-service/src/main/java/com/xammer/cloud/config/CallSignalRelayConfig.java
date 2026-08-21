package com.xammer.cloud.config;

import com.xammer.cloud.service.call.CallSignalRelay;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Subscribes this instance to the shared call-signal channel.
 *
 * A dedicated listener container rather than a shared one: call signals are
 * latency-sensitive (a ring the recipient sees three seconds late is a ring they
 * answer after the caller gave up), so they should not queue behind cache
 * invalidation traffic or Spring Session's own listener.
 *
 * The executor is bounded on purpose. The default
 * {@link SimpleAsyncTaskExecutor} creates a thread per message and never reuses
 * one, which under a burst — a fan-out to a large admin pool is one burst by
 * definition — spawns threads without limit. CallerRunsPolicy is the right
 * rejection behaviour here: delivering a ring slowly on the Redis listener thread
 * beats dropping it.
 */
@Configuration
public class CallSignalRelayConfig {

    @Bean(name = "callSignalListenerExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor callSignalListenerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("call-signal-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * The relay is registered as the listener directly, not wrapped in a
     * {@code MessageListenerAdapter}: the adapter would deserialise the body with its
     * own serializer (JDK serialization by default) while the publisher writes JSON
     * through a StringRedisTemplate, so nothing would ever decode. The relay reads the
     * raw bytes itself.
     */
    @Bean
    public RedisMessageListenerContainer callSignalListenerContainer(
            RedisConnectionFactory connectionFactory,
            CallSignalRelay relay,
            ThreadPoolTaskExecutor callSignalListenerExecutor) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(callSignalListenerExecutor);
        container.addMessageListener(relay, new ChannelTopic(CallSignalRelay.CHANNEL));
        return container;
    }
}
