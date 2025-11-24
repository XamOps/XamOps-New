package com.xammer.billops.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
public class SchedulingConfig {

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        // Set pool size to 5 to allow multiple refresh jobs (Dashboard, Billing, Invoices, Tickets)
        // to run in parallel if their schedules overlap.
        scheduler.setPoolSize(5); 
        scheduler.setThreadNamePrefix("billops-scheduled-task-");
        scheduler.initialize();
        return scheduler;
    }
}