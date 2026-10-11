package com.xammer.cloud.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CloudK8s automation wiring: binds the controller-readiness properties (design §6.7, plan P10).
 */
@Configuration
@EnableConfigurationProperties(K8sAutomationReadinessProperties.class)
public class K8sAutomationWebConfig implements WebMvcConfigurer {
}
