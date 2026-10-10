package com.xammer.cloud.service.automation.workload;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.security.CodeSource;
import java.util.List;

/**
 * Finds implementations of a type <strong>in the production source tree only</strong>.
 *
 * <p>Several tests assert the deliberate <em>absence</em> of an implementation — no
 * {@code WorkloadActionExecutor}, no real {@code UsageHistoryProvider} — because the absence of a
 * collaborator, rather than a flag check that could be mis-evaluated, is what makes it impossible
 * for this release to change a cluster or to fabricate a recommendation.
 *
 * <p>A plain classpath scan cannot express that: {@code target/test-classes} is on the same
 * classpath, so a test's own fake would make the assertion fail even though nothing shippable
 * changed. Filtering by code source is exact, where filtering by a name convention like
 * {@code *Test} would quietly start excluding production classes that happen to contain the word.
 */
public final class ProductionClassScan {

    private ProductionClassScan() {
    }

    /** @return the production class names implementing {@code type}, sorted */
    public static List<String> implementationsOf(Class<?> type) {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(type));
        return scanner.findCandidateComponents("com.xammer.cloud").stream()
                .map(BeanDefinition::getBeanClassName)
                .filter(ProductionClassScan::isProductionClass)
                .sorted()
                .toList();
    }

    private static boolean isProductionClass(String className) {
        if (className == null) {
            return false;
        }
        try {
            CodeSource source = Class.forName(className, false, ProductionClassScan.class.getClassLoader())
                    .getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return true;        // cannot tell; keep it, so the assertion fails loudly rather than quietly
            }
            return !source.getLocation().getPath().contains("test-classes");
        } catch (Throwable cannotLoad) {
            return true;            // same reasoning: an unclassifiable class must not be hidden
        }
    }
}
