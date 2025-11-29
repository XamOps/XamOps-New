package com.xammer.billops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan; // 1. IMPORT THIS
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling
@EnableCaching
@EntityScan(basePackages = {"com.xammer.billops", "com.xammer.cloud.domain"}) // 2. ADD THIS LINE
public class BillopsApplication {
 
    public static void main(String[] args) {  
        SpringApplication.run(BillopsApplication.class, args); 
    }
}  