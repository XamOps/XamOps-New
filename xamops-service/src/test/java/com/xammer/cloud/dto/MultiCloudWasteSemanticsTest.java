package com.xammer.cloud.dto;

import com.xammer.cloud.dto.azure.AzureWasteItem;
import com.xammer.cloud.dto.gcp.GcpWasteItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the three-way savings distinction on the GCP and Azure waste DTOs so it matches AWS.
 *
 * <p>Both clouds previously had only {@code savingsUnknown}, which conflated "real cost we
 * could not price" with "this resource is free". Several detectors bypassed even that and
 * passed a bare {@code 0.0}, making a free resource indistinguishable from a priced one that
 * genuinely costs nothing to keep.
 */
class MultiCloudWasteSemanticsTest {

    @Nested
    @DisplayName("GCP waste items")
    class Gcp {

        @Test
        @DisplayName("a priced item is billable")
        void pricedIsBillable() {
            GcpWasteItem item = new GcpWasteItem("disk-1", "data", "Unattached PD",
                    "us-central1", 4.00, "Detached for 30 days");

            assertThat(item.isBillableWaste()).isTrue();
            assertThat(item.isNoCostImpact()).isFalse();
            assertThat(item.isSavingsUnknown()).isFalse();
        }

        @Test
        @DisplayName("a free resource is excluded from totals and distinguishable from unpriced")
        void freeIsDistinctFromUnpriced() {
            GcpWasteItem free = GcpWasteItem.withNoCostImpact("fw-1", "allow-ssh",
                    "Disabled Firewall Rule", "global", "Rule is disabled");
            GcpWasteItem unpriced = GcpWasteItem.withUnknownSavings("job-1", "nightly",
                    "Failing Scheduler Job", "us-central1", "Last attempt failed");

            assertThat(free.isNoCostImpact()).isTrue();
            assertThat(free.isSavingsUnknown()).isFalse();
            assertThat(unpriced.isSavingsUnknown()).isTrue();
            assertThat(unpriced.isNoCostImpact()).isFalse();
            assertThat(free.isBillableWaste()).isFalse();
            assertThat(unpriced.isBillableWaste()).isFalse();
        }

        @Test
        @DisplayName("a mixed list yields a total and a count describing the same items")
        void totalAndCountAgree() {
            List<GcpWasteItem> items = List.of(
                    new GcpWasteItem("disk-1", "d1", "Unattached PD", "us-central1", 4.00, "Detached"),
                    new GcpWasteItem("ip-1", "i1", "Unused IP", "us-central1", 7.30, "Reserved, unused"),
                    GcpWasteItem.withNoCostImpact("fw-1", "f1", "Disabled Firewall Rule", "global", "Disabled"),
                    GcpWasteItem.withNoCostImpact("tp-1", "t1", "Empty Target Pool", "us-central1", "No backends"),
                    GcpWasteItem.withUnknownSavings("job-1", "j1", "Failing Scheduler Job", "us-central1", "Failed"));

            List<GcpWasteItem> billable = items.stream().filter(GcpWasteItem::isBillableWaste).toList();

            assertThat(billable).hasSize(2);
            assertThat(billable.stream().mapToDouble(GcpWasteItem::getMonthlySavings).sum()).isEqualTo(11.30);
        }
    }

    @Nested
    @DisplayName("Azure waste items")
    class Azure {

        @Test
        @DisplayName("a priced item is billable")
        void pricedIsBillable() {
            AzureWasteItem item = new AzureWasteItem("d-1", "disk1", "Unattached Disk",
                    "eastus", "Detached for 30 days", 5.89);

            assertThat(item.isBillableWaste()).isTrue();
            assertThat(item.isNoCostImpact()).isFalse();
        }

        @Test
        @DisplayName("resources Azure does not bill for are marked free, not unpriced")
        void freeResourcesAreMarkedFree() {
            // NICs, NSGs, availability sets, route tables and empty resource groups are free.
            // They were previously emitted as withUnknownSavings, which overstates the value
            // of acting on them.
            AzureWasteItem nic = AzureWasteItem.withNoCostImpact("nic-1", "nic1",
                    "Unattached NIC", "eastus", "No VM attached");

            assertThat(nic.isNoCostImpact()).isTrue();
            assertThat(nic.isSavingsUnknown()).isFalse();
            assertThat(nic.isBillableWaste()).isFalse();
            assertThat(nic.getMonthlySavings()).isZero();
        }

        @Test
        @DisplayName("a deallocated VM stays unpriced, because its disks still bill")
        void deallocatedVmRemainsUnpriced() {
            AzureWasteItem vm = AzureWasteItem.withUnknownSavings("vm-1", "vm1",
                    "Deallocated VM", "eastus", "Compute stopped, storage still billed");

            assertThat(vm.isSavingsUnknown()).isTrue();
            assertThat(vm.isNoCostImpact()).isFalse();
            assertThat(vm.isBillableWaste()).isFalse();
        }

        @Test
        @DisplayName("a mixed list yields a total and a count describing the same items")
        void totalAndCountAgree() {
            List<AzureWasteItem> items = List.of(
                    new AzureWasteItem("d-1", "disk1", "Unattached Disk", "eastus", "Detached", 5.89),
                    new AzureWasteItem("ip-1", "ip1", "Orphaned Public IP", "eastus", "Unassociated", 3.65),
                    AzureWasteItem.withNoCostImpact("nic-1", "nic1", "Unattached NIC", "eastus", "No VM"),
                    AzureWasteItem.withNoCostImpact("nsg-1", "nsg1", "Unused NSG", "eastus", "Unassociated"),
                    AzureWasteItem.withUnknownSavings("vm-1", "vm1", "Deallocated VM", "eastus", "Stopped"));

            List<AzureWasteItem> billable = items.stream().filter(AzureWasteItem::isBillableWaste).toList();

            assertThat(billable).hasSize(2);
            assertThat(billable.stream().mapToDouble(AzureWasteItem::getMonthlySavings).sum()).isEqualTo(9.54);
        }
    }
}
