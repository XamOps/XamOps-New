package com.xammer.cloud.util;

import com.xammer.cloud.domain.DevOpsScript;
import com.xammer.cloud.domain.elasticsearch.DevOpsScriptDocument;

/**
 * Mapper utility for converting between JPA entity and Elasticsearch document.
 * Keeps data synchronized between PostgreSQL and Elasticsearch.
 * 
 * Requirements: REQ-2.3.1 (Full-Text Search), REQ-2.6.1 (Performance)
 */
public class DevOpsScriptMapper {

    private DevOpsScriptMapper() {
        // Utility class - prevent instantiation
    }

    /**
     * Converts a JPA entity to an Elasticsearch document.
     * 
     * @param entity JPA entity
     * @return Elasticsearch document
     */
    public static DevOpsScriptDocument toDocument(DevOpsScript entity) {
        if (entity == null) {
            return null;
        }

        DevOpsScriptDocument document = new DevOpsScriptDocument();
        document.setId(entity.getId());
        document.setName(entity.getName());
        document.setDescription(entity.getDescription());
        document.setCategory(entity.getCategory());
        document.setCode(entity.getCode());
        document.setUsageInstructions(entity.getUsageInstructions());
        document.setVersion(entity.getVersion());
        document.setTags(entity.getTags());
        document.setCloudProviders(entity.getCloudProviders());
        document.setDifficulty(entity.getDifficulty());
        document.setAuthor(entity.getAuthor());
        document.setAuthorId(entity.getAuthorId());
        document.setEstimatedTimeMinutes(entity.getEstimatedTimeMinutes());
        document.setPrerequisites(entity.getPrerequisites());
        document.setViewCount(entity.getViewCount());
        document.setDownloadCount(entity.getDownloadCount());
        document.setCopyCount(entity.getCopyCount());
        document.setFavoriteCount(entity.getFavoriteCount());
        document.setAverageRating(entity.getAverageRating());
        document.setRatingCount(entity.getRatingCount());
        document.setCreatedAt(entity.getCreatedAt());
        document.setUpdatedAt(entity.getUpdatedAt());
        document.setLastUsedAt(entity.getLastUsedAt());
        document.setStatus(entity.getStatus());
        document.setIsUserContributed(entity.getIsUserContributed());
        document.setIsFeatured(entity.getIsFeatured());

        return document;
    }

    /**
     * Converts an Elasticsearch document to a JPA entity.
     * Note: This creates a detached entity. Use with caution.
     * 
     * @param document Elasticsearch document
     * @return JPA entity (detached)
     */
    public static DevOpsScript toEntity(DevOpsScriptDocument document) {
        if (document == null) {
            return null;
        }

        DevOpsScript entity = new DevOpsScript();
        entity.setId(document.getId());
        entity.setName(document.getName());
        entity.setDescription(document.getDescription());
        entity.setCategory(document.getCategory());
        entity.setCode(document.getCode());
        entity.setUsageInstructions(document.getUsageInstructions());
        entity.setVersion(document.getVersion());
        entity.setTags(document.getTags());
        entity.setCloudProviders(document.getCloudProviders());
        entity.setDifficulty(document.getDifficulty());
        entity.setAuthor(document.getAuthor());
        entity.setAuthorId(document.getAuthorId());
        entity.setEstimatedTimeMinutes(document.getEstimatedTimeMinutes());
        entity.setPrerequisites(document.getPrerequisites());
        entity.setViewCount(document.getViewCount());
        entity.setDownloadCount(document.getDownloadCount());
        entity.setCopyCount(document.getCopyCount());
        entity.setFavoriteCount(document.getFavoriteCount());
        entity.setAverageRating(document.getAverageRating());
        entity.setRatingCount(document.getRatingCount());
        entity.setCreatedAt(document.getCreatedAt());
        entity.setUpdatedAt(document.getUpdatedAt());
        entity.setLastUsedAt(document.getLastUsedAt());
        entity.setStatus(document.getStatus());
        entity.setIsUserContributed(document.getIsUserContributed());
        entity.setIsFeatured(document.getIsFeatured());

        return entity;
    }
}
