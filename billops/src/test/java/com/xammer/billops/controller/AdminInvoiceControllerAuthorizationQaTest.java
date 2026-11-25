package com.xammer.billops.controller;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA depth-testing: role guard on the invoice admin API (report 02-bugs-A, BUG A-02).
 *
 * <p>Reflection-level check that every state-changing handler of {@link AdminInvoiceController}
 * carries a role {@code @PreAuthorize}. Today only holding-cost, private-pricing and upload-draft
 * do; finalize, delete, discount, update, merge, generate do not, so any authenticated user can call
 * them. Bug-exposing cases are {@code @Disabled}.
 */
class AdminInvoiceControllerAuthorizationQaTest {

    private static PreAuthorize guardOf(String name) {
        Method m = Arrays.stream(AdminInvoiceController.class.getDeclaredMethods())
                .filter(x -> x.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("handler not found: " + name));
        PreAuthorize p = m.getAnnotation(PreAuthorize.class);
        return p != null ? p : AdminInvoiceController.class.getAnnotation(PreAuthorize.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"setHoldingCost", "applyPrivatePricing", "setPrivatePricingExclusion",
            "removePrivatePricing", "previewUploadedDraft", "createUploadedDraft"})
    @DisplayName("characterization: these handlers are already restricted to billops/super admin")
    void guardedHandlers(String handler) {
        PreAuthorize p = guardOf(handler);
        assertThat(p).isNotNull();
        assertThat(p.value()).contains("ROLE_BILLOPS_ADMIN");
    }

    @ParameterizedTest
    @ValueSource(strings = {"finalizeInvoice", "deleteInvoice", "applyDiscount", "removeDiscount",
            "updateInvoice", "mergeInvoices", "generateDraftInvoice"})
    @Disabled("BUG A-02: invoice mutation endpoints have no role check; any authenticated user can finalize/delete/discount/merge")
    @DisplayName("state-changing invoice handlers must require an admin role")
    void mutationHandlersRequireAdminRole(String handler) {
        PreAuthorize p = guardOf(handler);
        assertThat(p).as("@PreAuthorize on " + handler).isNotNull();
        assertThat(p.value()).contains("ROLE_BILLOPS_ADMIN");
    }

    @Test
    @DisplayName("characterization: current gap is exactly the unguarded set (flips when A-02 is fixed)")
    void currentUnguardedSet() {
        for (String h : new String[] {"finalizeInvoice", "deleteInvoice", "applyDiscount"}) {
            // If this fails, A-02 has been fixed: enable the @Disabled test above and delete this one.
            assertThat(guardOf(h)).as(h).isNull();
        }
    }
}
