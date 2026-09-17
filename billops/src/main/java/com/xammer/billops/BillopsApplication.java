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
        // Force IST so billops agrees with xamops-service (which sets the same in its main).
        // Both services write timestamps to the shared `ticket` table; without this billops
        // followed the server OS zone (UTC on UAT/prod), so SLA/first-reply times computed
        // against xamops's IST timestamps were skewed by the 5.5h India offset. Pinning both
        // to IST keeps every timestamp in one zone across services and environments.
        System.setProperty("user.timezone", "Asia/Kolkata");
        SpringApplication.run(BillopsApplication.class, args);
    }
}
