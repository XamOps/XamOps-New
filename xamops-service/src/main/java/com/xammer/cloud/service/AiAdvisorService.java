// package com.xammer.cloud.service;

// import com.theokanning.openai.completion.chat.ChatCompletionRequest;
// import com.theokanning.openai.completion.chat.ChatMessage;
// import com.theokanning.openai.service.OpenAiService;
// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;
// import org.springframework.beans.factory.annotation.Value;
// import org.springframework.stereotype.Service;

// import java.time.Duration;
// import java.util.List;

// @Service
// public class AiAdvisorService {

//     private static final Logger logger = LoggerFactory.getLogger(AiAdvisorService.class);

//     private final OpenAiService openAiService;

//     public AiAdvisorService(@Value("${openai.api.key}") String apiKey) {
//         this.openAiService = new OpenAiService(apiKey, Duration.ofSeconds(60));
//     }

//     public String getRightsizingRecommendations(Object rightsizingData) {
//         String prompt = "As an expert AWS FinOps consultant, analyze the following JSON data for a single underutilized resource. Provide a detailed, actionable rightsizing recommendation. Explain the Current vs. Recommended types, the potential savings, and the specific metrics (like CPU utilization) that justify this change. Format your response in clear, easy-to-read markdown.\n\n" + rightsizingData.toString();
//         return getAiResponse(prompt);
//     }

//     public String getSecurityRecommendations(Object securityData) {
//         String prompt = "As an expert AWS Security consultant, analyze the following JSON data for a single security finding. Provide a detailed, step-by-step remediation guide to fix this specific issue. Include CLI commands or console steps where applicable. Explain the risk and the compliance impact. Format your response in clear, easy-to-read markdown.\n\n" + securityData.toString();
//         return getAiResponse(prompt);
//     }

//     private String getAiResponse(String prompt) {
//         try {
//             logger.info("Sending prompt to OpenAI API...");
//             logger.debug("AI Prompt: {}", prompt); // Detailed logging of the prompt

//             ChatCompletionRequest completionRequest = ChatCompletionRequest.builder()
//                     .model("gpt-3.5-turbo")
//                     .messages(List.of(new ChatMessage("user", prompt)))
//                     .build();

//             String response = openAiService.createChatCompletion(completionRequest)
//                     .getChoices().get(0).getMessage().getContent();

//             logger.info("Successfully received response from OpenAI.");
//             logger.debug("AI Response: {}", response); // Detailed logging of the response
//             return response;

//         } catch (Exception e) {
//             logger.error("Error calling OpenAI API", e);
//             return "Error: Could not get AI recommendations at this time. Please check the application logs for more details.";
//         }
//     }
// }