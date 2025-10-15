package com.xammer.billops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient; // 1. IMPORT THIS

@SpringBootApplication
@EnableDiscoveryClient // 2. ADD THIS ANNOTATION
@EnableCaching
public class BillopsApplication {

    public static void main(String[] args) {
        SpringApplication.run(BillopsApplication.class, args);
    }
}