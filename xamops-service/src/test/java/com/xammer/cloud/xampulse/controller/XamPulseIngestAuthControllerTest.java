package com.xammer.cloud.xampulse.controller;

import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.service.agent.IngestAuthorizer;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class XamPulseIngestAuthControllerTest {

    private MockMvc mvc(IngestAuthorizer.Decision decision) {
        IngestAuthorizer authorizer = mock(IngestAuthorizer.class);
        when(authorizer.authorize("tok")).thenReturn(decision);
        XamPulseProperties props = new XamPulseProperties();
        props.setInternalSecret("s3cret");
        return MockMvcBuilders.standaloneSetup(new XamPulseIngestAuthController(props, authorizer)).build();
    }

    private static final IngestAuthorizer.Decision OVER =
            new IngestAuthorizer.Decision(IngestAuthorizer.Status.QUOTA_EXCEEDED, 77L, "i-1", 14, 120);

    @Test
    void directCallAnswers429WithRetryAfter() throws Exception {
        mvc(OVER).perform(get("/internal/xampulse/auth").header("X-Xp-Internal", "s3cret").header("X-Xp-Token", "tok"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "120"));
    }

    @Test
    void nginxModeAnswers403WithTheSameAdvisoryHeadersSoTheEdgeCanMapIt() throws Exception {
        mvc(OVER).perform(get("/internal/xampulse/auth").header("X-Xp-Internal", "s3cret").header("X-Xp-Token", "tok")
                        .header("X-Xp-Auth-Mode", "nginx"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Retry-After", "120"))
                .andExpect(header().string("X-Xp-Quota", "exceeded"));
    }

    @Test
    void wrongSecretIsStill404EvenWhenOverQuota() throws Exception {
        mvc(OVER).perform(get("/internal/xampulse/auth").header("X-Xp-Internal", "nope").header("X-Xp-Token", "tok"))
                .andExpect(status().isNotFound());
    }

    @Test
    void okPathKeepsTheTrustedHeaders() throws Exception {
        mvc(new IngestAuthorizer.Decision(IngestAuthorizer.Status.OK, 77L, "i-1", 14))
                .perform(get("/internal/xampulse/auth").header("X-Xp-Internal", "s3cret").header("X-Xp-Token", "tok"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Xp-Tenant", "77"))
                .andExpect(header().string("X-Xp-Instance", "i-1"))
                .andExpect(header().string("X-Xp-Retain-Days", "14"));
    }
}
