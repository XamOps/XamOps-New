package com.xammer.billops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan; // 1. IMPORT THIS
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling
@EnableCaching
@EnableMethodSecurity(prePostEnabled = true)
// com.xammer.cloud.permissions is the shared permission-gap tracker; without it
// BillOps would record nothing when a customer's IAM role loses cost access.
// com.xammer.cloud.messaging is the shared SMS/WhatsApp provider client, used here by
// SupportAutoNotificationScheduler and in xamops-service by the alert and report senders.
@ComponentScan(basePackages = { "com.xammer.billops", "com.xammer.cloud.permissions",
        "com.xammer.cloud.messaging" })
@EntityScan(basePackages = { "com.xammer.billops", "com.xammer.cloud.domain" }) // 2. ADD THIS LINE
public class BillopsApplication {

    public static void main(String[] args) {
        SpringApplication.run(BillopsApplication.class, args);
    }
}
