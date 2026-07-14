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
    void chat_whenUnmatched_behavesExactlyAsBefore() {
        when(featureFaqMatcher.match("my billing is wrong")).thenReturn(Optional.empty());

        ChatRequest request = new ChatRequest();
        request.setMessage("my billing is wrong");
        request.setAccountId("acc-1");

        ChatResponse response = controller.chat(request);

        verify(supportChatService).sendUserMessage("my billing is wrong", "acc-1");
        verify(supportChatService, never()).recordAutoResolvedQuestion(anyString(), anyString());
        verify(supportChatService, never()).sendBotAnswer(anyString());
        assertThat(response.getResponse()).isEqualTo("Request received. An agent will be with you shortly.");
    }
}
