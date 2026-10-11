package com.xammer.cloud.config;

import com.xammer.cloud.security.K8sAutomationGuardInterceptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * CloudK8s automation wiring: binds the controller-readiness properties (design §6.7, plan P10)
 * and puts {@link K8sAutomationGuardInterceptor} in front of every automation endpoint
 * (design §6.6), following {@code DevOpsRateLimitConfig}.
 *
 * <p>
 * New endpoints under a guarded path are covered by default; there is no per-method annotation
 * to forget. The vertical engine can satisfy R2 by adding its path here: the interceptor accepts
 * its {@code {cluster}} path variable and {@code accountId} query parameter as they are.
 */
@Configuration
@EnableConfigurationProperties(K8sAutomationReadinessProperties.class)
public class K8sAutomationWebConfig implements WebMvcConfigurer {

    /** Paths the guard runs on, for every HTTP method. */
    public static final List<String> GUARDED_PATHS = List.of("/api/eks/autoscaling/**");

    private final K8sAutomationGuardInterceptor guardInterceptor;

    public K8sAutomationWebConfig(K8sAutomationGuardInterceptor guardInterceptor) {
        this.guardInterceptor = guardInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(guardInterceptor).addPathPatterns(GUARDED_PATHS);
    }
}
