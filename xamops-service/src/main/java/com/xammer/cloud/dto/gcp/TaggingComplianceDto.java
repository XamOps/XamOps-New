//package com.xammer.cloud.dto.gcp;
//
//import lombok.Data;
//import java.util.List;
//import java.util.Map;
//
///**
// * DTO for representing the tagging compliance report for GCP resources.
// */
//@Data
//public class TaggingComplianceDto {
//    private double compliancePercentage;
//    private int totalResourcesScanned;
//    private int untaggedResourcesCount;
//    private List<UntaggedResource> untaggedResources;
//    private Map<String, Long> complianceByTagKey;
//
//    @Data
//    public static class UntaggedResource {
//        private String resourceId;
//        private String resourceType;
//        private String location;
//        private List<String> missingTags;
//
//        public UntaggedResource(String resourceId, String resourceType, String location, List<String> missingTags) {
//            this.resourceId = resourceId;
//            this.resourceType = resourceType;
//            this.location = location;
//            this.missingTags = missingTags;
//        }
//    }
//}