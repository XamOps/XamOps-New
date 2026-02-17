package com.xammer.cloud.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "awsTaskExecutor")
    public Executor awsTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("AwsAsync-");
        executor.initialize();
        return executor;
    }

    @Bean(name = "prowlerTaskExecutor")
    public Executor prowlerTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2); // Allow 2 concurrent scans
        executor.setMaxPoolSize(5); // Burst to 5
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("ProwlerAsync-");
        executor.initialize();
        return executor;
    }
}