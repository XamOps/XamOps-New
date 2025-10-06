// package com.xammer.cloud.controller;

// import com.xammer.cloud.service.AiAdvisorService;
// import org.springframework.web.bind.annotation.PostMapping;
// import org.springframework.web.bind.annotation.RequestBody;
// import org.springframework.web.bind.annotation.RequestMapping;
// import org.springframework.web.bind.annotation.RestController;

// @RestController
// @RequestMapping("/api/ai-advisor")
// public class AiAdvisorController {

//     private final AiAdvisorService aiAdvisorService;

//     public AiAdvisorController(AiAdvisorService aiAdvisorService) {
//         this.aiAdvisorService = aiAdvisorService;
//     }

//     @PostMapping("/rightsizing")
//     public String getRightsizingAnalysis(@RequestBody Object rightsizingData) {
//         return aiAdvisorService.getRightsizingRecommendations(rightsizingData);
//     }

//     @PostMapping("/security")
//     public String getSecurityAnalysis(@RequestBody Object securityData) {
//         return aiAdvisorService.getSecurityRecommendations(securityData);
//     }
// }