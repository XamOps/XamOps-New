package com.xammer.cloud.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the three-way distinction between billable waste, unpriced waste and findings with
 * no cost impact.
 *
 * <p>All three previously carried {@code monthlySavings = 0.0} with nothing to tell them
 * apart, which is how the dashboard came to report "257 wasted resources — $18.83/mo": the
 * count was dominated by free resources such as unattached ENIs and unused security groups,
 * while the amount came from a handful of billable ones.
 */
class WastedResourceSemanticsTest {

    @Test
    @DisplayName("a priced resource is billable waste")
    void pricedResourceIsBillable() {
        DashboardData.WastedResource r = new DashboardData.WastedResource(
                "vol-1", "data", "EBS Volume", "ap-south-1", 9.12, "Unattached Volume");

        assertThat(r.isBillableWaste()).isTrue();
        assertThat(r.isSavingsUnknown()).isFalse();
        assertThat(r.isNoCostImpact()).isFalse();
        assertThat(r.getMonthlySavings()).isEqualTo(9.12);
    }

    @Test
    @DisplayName("an unpriced resource is excluded from totals but is not free")
    void unpricedResourceIsExcludedFromTotals() {
        DashboardData.WastedResource r = DashboardData.WastedResource.withUnknownSavings(
                "i-1", "web", "EC2 Instance", "ap-south-1", "Idle EC2 Instance (low CPU)");

        assertThat(r.isBillableWaste()).isFalse();
        assertThat(r.isSavingsUnknown()).isTrue();
        assertThat(r.isNoCostImpact()).isFalse();
    }

    @Test
    @DisplayName("a free resource is excluded from totals and is distinguishable from unpriced")
    void freeResourceIsDistinctFromUnpriced() {
        DashboardData.WastedResource r = DashboardData.WastedResource.withNoCostImpact(
                "eni-1", "eni-1", "ENI", "ap-south-1", "Unattached ENI");

        assertThat(r.isBillableWaste()).isFalse();
        assertThat(r.isNoCostImpact()).isTrue();
        assertThat(r.isSavingsUnknown()).isFalse();
        assertThat(r.getMonthlySavings()).isZero();
    }

    @Test
    @DisplayName("a total over a mixed list counts only billable waste, and its count matches")
    void mixedListReconciles() {
        List<DashboardData.WastedResource> findings = List.of(
                new DashboardData.WastedResource("vol-1", "d1", "EBS Volume", "ap-south-1", 3.74, "Unattached"),
                new DashboardData.WastedResource("snap-1", "s1", "Snapshot", "ap-south-1", 5.75, "Older than 90 days"),
                DashboardData.WastedResource.withNoCostImpact("eni-1", "eni-1", "ENI", "ap-south-1", "Unattached ENI"),
                DashboardData.WastedResource.withNoCostImpact("sg-1", "sg-1", "Security Group", "ap-south-1", "Unused"),
                DashboardData.WastedResource.withNoCostImpact("lg-1", "lg-1", "Log Group", "ap-south-1", "Empty"),
                DashboardData.WastedResource.withUnknownSavings("i-1", "web", "EC2 Instance", "ap-south-1", "Idle"));

        List<DashboardData.WastedResource> billable = findings.stream()
                .filter(DashboardData.WastedResource::isBillableWaste)
                .toList();

        double total = billable.stream()
                .mapToDouble(DashboardData.WastedResource::getMonthlySavings)
                .sum();

        // The count and the amount now describe the same two resources, rather than a count of
        // six sitting next to an amount contributed by two.
        assertThat(billable).hasSize(2);
        assertThat(total).isEqualTo(9.49);

        assertThat(findings.stream().filter(DashboardData.WastedResource::isNoCostImpact)).hasSize(3);
        assertThat(findings.stream().filter(DashboardData.WastedResource::isSavingsUnknown)).hasSize(1);
    }

    @Test
    @DisplayName("isBillableWaste is not serialised into API payloads")
    void derivedFlagIsNotSerialised() throws Exception {
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                DashboardData.WastedResource.withNoCostImpact(
                        "eni-1", "eni-1", "ENI", "ap-south-1", "Unattached ENI"));

        assertThat(json).doesNotContain("billableWaste");
        assertThat(json).contains("\"noCostImpact\":true");
        assertThat(json).contains("\"savingsUnknown\":false");
    }
}
