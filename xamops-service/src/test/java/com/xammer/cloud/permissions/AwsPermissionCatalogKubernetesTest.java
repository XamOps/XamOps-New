package com.xammer.cloud.permissions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CloudK8s feature mapping in {@link AwsPermissionCatalog}.
 *
 * <p>The trap these guard against: CloudK8s reads EKS node names out of
 * {@code cloudwatch:ListMetrics} ({@code EksService.fetchNodeNames}), so a denial on the
 * CloudWatch client is a Kubernetes-page outage. While {@code cloudwatch} did not list
 * {@code KUBERNETES} among its impacted features, any caller that narrowed to the feature —
 * {@code PermissionGapService.statusFor(account, KUBERNETES)} and the {@code ?feature=} query
 * parameter on the account-health endpoint — filtered that gap out, so the page showed no nodes
 * and no explanation.
 */
class AwsPermissionCatalogKubernetesTest {

    @Test
    @DisplayName("Losing cloudwatch degrades KUBERNETES, so a CloudK8s-scoped query keeps the gap")
    void cloudwatchDegradesKubernetes() {
        assertThat(AwsPermissionCatalog.featuresFor("cloudwatch"))
                .contains(XamOpsFeature.KUBERNETES);
    }

    @Test
    @DisplayName("The pre-existing cloudwatch features are untouched by that addition")
    void cloudwatchKeepsItsOtherFeatures() {
        assertThat(AwsPermissionCatalog.featuresFor("cloudwatch")).contains(
                XamOpsFeature.PERFORMANCE,
                XamOpsFeature.ALERTS,
                XamOpsFeature.RIGHTSIZING,
                XamOpsFeature.WASTE,
                XamOpsFeature.DASHBOARD,
                XamOpsFeature.DBOPS,
                XamOpsFeature.OBSERVABILITY);
    }

    @Test
    @DisplayName("Losing logs degrades KUBERNETES: the Container Insights check calls logs:DescribeLogGroups")
    void logsDegradesKubernetes_andKeepsItsOtherFeatures() {
        assertThat(AwsPermissionCatalog.featuresFor("logs")).contains(
                XamOpsFeature.KUBERNETES,
                XamOpsFeature.OBSERVABILITY,
                XamOpsFeature.ALERTS,
                XamOpsFeature.SRE);
        PermissionGap gap = PermissionGaps.denied("123456789012", "logs", "DescribeLogGroups",
                "us-east-1", "AccessDeniedException", "denied", PermissionGap.Kind.PERMISSION);
        assertThat(gap.getAction()).isEqualTo("logs:DescribeLogGroups");
        assertThat(gap.getFeatures()).contains(XamOpsFeature.KUBERNETES.name());
    }

    @Test
    void eksStillDegradesKubernetes() {
        assertThat(AwsPermissionCatalog.featuresFor("eks"))
                .contains(XamOpsFeature.KUBERNETES);
    }

    @Test
    @DisplayName("A cloudwatch denial carries KUBERNETES through to the recorded gap")
    void recordedCloudwatchGapCarriesKubernetes() {
        PermissionGap gap = PermissionGaps.denied("123456789012", "cloudwatch", "ListMetrics",
                "ap-south-1", "AccessDenied", "denied", PermissionGap.Kind.PERMISSION);

        assertThat(gap.getAction()).isEqualTo("cloudwatch:ListMetrics");
        assertThat(gap.getFeatures()).contains(XamOpsFeature.KUBERNETES.name());
        assertThat(gap.getFeatureLabels()).contains(XamOpsFeature.KUBERNETES.getLabel());
    }
}
