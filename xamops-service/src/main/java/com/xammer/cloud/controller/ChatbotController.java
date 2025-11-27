package com.xammer.cloud.controller;

import com.xammer.cloud.dto.ChatRequest;
import com.xammer.cloud.dto.ChatResponse;
import com.xammer.cloud.service.AnythingLlmService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
public class ChatbotController {

    private final AnythingLlmService anythingLlmService;

    public ChatbotController(AnythingLlmService anythingLlmService) {
        this.anythingLlmService = anythingLlmService;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String aiReply = anythingLlmService.getChatResponse(request.getMessage());
        return new ChatResponse(aiReply);
    }
}