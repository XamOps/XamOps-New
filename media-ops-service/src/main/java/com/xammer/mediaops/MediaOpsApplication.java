package com.xammer.mediaops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
// com.xammer.cloud.permissions is the shared permission-gap tracker; without it
// MediaOps would record nothing when a customer's IAM role loses S3 access.
@ComponentScan(basePackages = { "com.xammer.mediaops", "com.xammer.cloud.permissions" })
@EnableDiscoveryClient
@EnableAsync
@EnableScheduling
@EnableCaching
public class MediaOpsApplication {

    public static void main(String[] args) {
        System.setProperty("user.timezone", "Asia/Kolkata");
        SpringApplication.run(MediaOpsApplication.class, args);
    }
}
