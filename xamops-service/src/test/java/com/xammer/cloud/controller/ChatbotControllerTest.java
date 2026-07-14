package com.xammer.cloud.controller;

import com.xammer.cloud.dto.ChatRequest;
import com.xammer.cloud.dto.ChatResponse;
import com.xammer.cloud.service.SupportChatService;
import com.xammer.cloud.service.faq.FeatureFaqEntry;
import com.xammer.cloud.service.faq.FeatureFaqMatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatbotControllerTest {

    private SupportChatService supportChatService;
    private FeatureFaqMatcher featureFaqMatcher;
    private ChatbotController controller;

    @BeforeEach
    void setUp() {
        supportChatService = mock(SupportChatService.class);
        featureFaqMatcher = mock(FeatureFaqMatcher.class);
        controller = new ChatbotController(supportChatService, featureFaqMatcher);
    }

    @Test
    void chat_whenMatched_autoAnswersAndDoesNotForwardToHuman() {
        FeatureFaqEntry entry = new FeatureFaqEntry(
                "finops-disk-rightsizing", "FinOps", "Disk Rightsizing",
                List.of("disk", "ebs"), "Disk Rightsizing analyzes your volumes.");
        when(featureFaqMatcher.match("what does disk rightsizing do")).thenReturn(Optional.of(entry));

        ChatRequest request = new ChatRequest();
        request.setMessage("what does disk rightsizing do");
        request.setAccountId("acc-1");

        ChatResponse response = controller.chat(request);

        verify(supportChatService).recordAutoResolvedQuestion("what does disk rightsizing do", "acc-1");
        verify(supportChatService).sendBotAnswer(contains("Disk Rightsizing analyzes your volumes."));
        verify(supportChatService, never()).sendUserMessage(anyString(), anyString());
        assertThat(response.getResponse()).contains("Disk Rightsizing analyzes your volumes.");
        assertThat(response.getResponse()).contains("Did this answer your question?");
    }

    @Test
    void chat_whenUnmatched_forwardsToHumanAndPersistsVisibleAck() {
        when(featureFaqMatcher.match("my billing is wrong")).thenReturn(Optional.empty());

        ChatRequest request = new ChatRequest();
        request.setMessage("my billing is wrong");
        request.setAccountId("acc-1");

        ChatResponse response = controller.chat(request);

        // Still escalates to a human exactly as before this feature: the user's own
        // question is saved via sendUserMessage (default UNREAD status), not the
        // auto-resolved path.
        verify(supportChatService).sendUserMessage("my billing is wrong", "acc-1");
        verify(supportChatService, never()).recordAutoResolvedQuestion(anyString(), anyString());
        // New: a visible ack is now persisted so the widget isn't silent while waiting
        // on a human — this does not affect the BillOps admin unread badge, since it's
        // the user's own UNREAD question (not this bot message) that signals the queue.
        verify(supportChatService).sendBotAnswer("Request received. An agent will be with you shortly.");
        assertThat(response.getResponse()).isEqualTo("Request received. An agent will be with you shortly.");
    }
}
