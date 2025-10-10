//package com.xammer.cloud.controller.gcp;
//
//import com.xammer.cloud.dto.gcp.TaggingComplianceDto;
//import com.xammer.cloud.service.gcp.GcpDataService;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.GetMapping;
//import org.springframework.web.bind.annotation.RequestMapping;
//import org.springframework.web.bind.annotation.RequestParam;
//import org.springframework.web.bind.annotation.RestController;
//import java.util.concurrent.CompletableFuture;
//
//@RestController
//@RequestMapping("/api/xamops/gcp/tagging")
//public class GcpTaggingController {
//
//    private final GcpDataService gcpDataService;
//
//    public GcpTaggingController(GcpDataService gcpDataService) {
//        this.gcpDataService = gcpDataService;
//    }
//
//    @GetMapping("/compliance")
//    public CompletableFuture<ResponseEntity<TaggingComplianceDto>> getTaggingCompliance(@RequestParam String accountId) {
//        return gcpDataService.getTagComplianceReport(accountId)
//                .thenApply(ResponseEntity::ok);
//    }
//}