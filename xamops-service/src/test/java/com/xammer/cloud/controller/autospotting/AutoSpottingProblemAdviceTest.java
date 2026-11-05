package com.xammer.cloud.controller.autospotting;

import com.xammer.cloud.dto.autospotting.v2.SwitchChangeRequest;
import com.xammer.cloud.service.autospotting.AutoSpottingBlockedException;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.lease.LeaseLostException;
import com.xammer.cloud.service.autospotting.core.lease.LeaseUnavailableException;
import jakarta.validation.Valid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The AutoSpotting error envelope (plan-phase-09.md task 9.2, design-core.md §11): every exception a
 * v2/operator controller raises comes back as RFC 9457 {@code application/problem+json} with a
 * stable {@code code}, through {@link AutoSpottingProblemAdvice}. The throwing controller below lives
 * in {@code com.xammer.cloud.controller.autospotting} so the advice's {@code basePackages} selector
 * matches it (as it does the real controllers). Validation failures become 400 {@code CHECK_FAILED}
 * with field errors; no raw exception text leaks.
 */
class AutoSpottingProblemAdviceTest {

    @RestController
    @RequestMapping("/test/autospotting")
    static class ThrowingController {
        @PostMapping("/api")
        String api() {
            throw AutoSpottingApiException.switchVersionMismatch();
        }

        @PostMapping("/blocked")
        String blocked() {
            throw AutoSpottingBlockedException.planRequired("/plan/42");
        }

        @PostMapping("/lease-unavailable")
        String leaseUnavailable() {
            throw new LeaseUnavailableException("store down", new RuntimeException("raw secret token"));
        }

        @PostMapping("/lease-lost")
        String leaseLost() {
            throw new LeaseLostException("lost");
        }

        @PostMapping("/denied")
        String denied() {
            throw new AccessDeniedException("Account not found or access denied.");
        }

        @PostMapping("/validate")
        String validate(@Valid @RequestBody SwitchChangeRequest body) {
            return "ok";
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new AutoSpottingProblemAdvice())
                .build();
    }

    @Test
    void apiException_mapsToStableCodeAndProblemJson() throws Exception {
        mvc.perform(post("/test/autospotting/api"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("SWITCH_VERSION_MISMATCH"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.type").value("https://xamops.com/errors/as/SWITCH_VERSION_MISMATCH"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    void blockedException_mapsCodeAndStatus() throws Exception {
        mvc.perform(post("/test/autospotting/blocked"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLAN_REQUIRED"));
    }

    @Test
    void leaseUnavailable_is503RetryableAndNoRawText() throws Exception {
        mvc.perform(post("/test/autospotting/lease-unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AS_LEASE_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("raw secret token"))));
    }

    @Test
    void leaseLost_is503Retryable() throws Exception {
        mvc.perform(post("/test/autospotting/lease-lost"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AS_LEASE_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void accessDenied_is403ForbiddenRole_opaque() throws Exception {
        mvc.perform(post("/test/autospotting/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AS_FORBIDDEN_ROLE"));
    }

    @Test
    void validationFailure_is400CheckFailedWithFieldErrors() throws Exception {
        // Missing 'engaged', bad reason length 0, missing expectedVersion.
        mvc.perform(post("/test/autospotting/validate")
                        .contentType("application/json")
                        .content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHECK_FAILED"))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors.length()").value(org.hamcrest.Matchers.greaterThan(0)));
    }
}
