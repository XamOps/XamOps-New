package com.xammer.cloud.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the ownership check that keeps controller-managed load balancers out of the idle report.
 *
 * <p>A load balancer created by the AWS Load Balancer Controller has legitimately empty target
 * groups whenever the deployment behind it is scaled to zero, which the idle detector reads as
 * waste. Deleting it takes the service offline until the controller recreates it, so the
 * recommendation is both wrong and disruptive.
 */
class ControllerOwnedTagTest {

    @Test
    @DisplayName("in-tree Kubernetes service controller tags are recognised")
    void inTreeControllerTags() {
        assertThat(OptimizationService.isControllerOwnedTagKey("kubernetes.io/cluster/prod")).isTrue();
        assertThat(OptimizationService.isControllerOwnedTagKey("kubernetes.io/service-name")).isTrue();
        assertThat(OptimizationService.isControllerOwnedTagKey("kubernetes.io/role/elb")).isTrue();
    }

    @Test
    @DisplayName("AWS Load Balancer Controller tags are recognised")
    void loadBalancerControllerTags() {
        assertThat(OptimizationService.isControllerOwnedTagKey("elbv2.k8s.aws/cluster")).isTrue();
        assertThat(OptimizationService.isControllerOwnedTagKey("ingress.k8s.aws/stack")).isTrue();
        assertThat(OptimizationService.isControllerOwnedTagKey("ingress.k8s.aws/resource")).isTrue();
        assertThat(OptimizationService.isControllerOwnedTagKey("service.k8s.aws/stack")).isTrue();
    }

    @Test
    @DisplayName("CloudFormation and Elastic Beanstalk ownership is recognised")
    void otherManagersRecreateToo() {
        // Not Kubernetes, but the same failure mode: the stack recreates what you delete.
        assertThat(OptimizationService.isControllerOwnedTagKey("aws:cloudformation:stack-name")).isTrue();
        assertThat(OptimizationService.isControllerOwnedTagKey("aws:elasticbeanstalk:environment-name")).isTrue();
    }

    @Test
    @DisplayName("tag keys are matched case-insensitively")
    void caseInsensitive() {
        assertThat(OptimizationService.isControllerOwnedTagKey("Kubernetes.io/Cluster/Prod")).isTrue();
        assertThat(OptimizationService.isControllerOwnedTagKey("ELBV2.K8S.AWS/CLUSTER")).isTrue();
    }

    @Test
    @DisplayName("ordinary tags do not shield a load balancer")
    void ordinaryTagsAreNotOwnership() {
        // Over-matching here would silently suppress genuine findings, so the prefixes must be
        // specific. "Name" and cost-allocation tags are on nearly every resource.
        assertThat(OptimizationService.isControllerOwnedTagKey("Name")).isFalse();
        assertThat(OptimizationService.isControllerOwnedTagKey("Environment")).isFalse();
        assertThat(OptimizationService.isControllerOwnedTagKey("CostCenter")).isFalse();
        assertThat(OptimizationService.isControllerOwnedTagKey("team")).isFalse();
        assertThat(OptimizationService.isControllerOwnedTagKey("aws:createdBy")).isFalse();
    }

    @Test
    @DisplayName("a prefix must be at the start of the key")
    void prefixMustAnchor() {
        assertThat(OptimizationService.isControllerOwnedTagKey("legacy-kubernetes.io/cluster")).isFalse();
        assertThat(OptimizationService.isControllerOwnedTagKey("notes/kubernetes.io/cluster")).isFalse();
    }

    @Test
    @DisplayName("missing or blank keys are not ownership claims")
    void blankKeysAreSafe() {
        assertThat(OptimizationService.isControllerOwnedTagKey(null)).isFalse();
        assertThat(OptimizationService.isControllerOwnedTagKey("")).isFalse();
        assertThat(OptimizationService.isControllerOwnedTagKey("   ")).isFalse();
    }
}
