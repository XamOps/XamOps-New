package com.xammer.cloud.service;

import com.xammer.cloud.domain.DevOpsScript;
import com.xammer.cloud.domain.elasticsearch.DevOpsScriptDocument;
import com.xammer.cloud.repository.DevOpsScriptRepository;
import com.xammer.cloud.repository.elasticsearch.DevOpsScriptElasticsearchRepository;
import com.xammer.cloud.util.DevOpsScriptMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for managing Elasticsearch indexing operations for DevOps scripts.
 * Handles single and bulk indexing, updates, and deletions.
 * 
 * Requirements: REQ-2.3.1 (Full-Text Search), REQ-2.6.1 (Performance)
 */
@Service
public class DevOpsScriptIndexingService {

    private static final Logger logger = LoggerFactory.getLogger(DevOpsScriptIndexingService.class);

    @Autowired
    private DevOpsScriptElasticsearchRepository elasticsearchRepository;

    @Autowired
    private DevOpsScriptRepository devOpsScriptRepository;

    /**
     * Index a single script in Elasticsearch.
     * 
     * @param script Script to index
     * @return Indexed document
     */
    public DevOpsScriptDocument indexScript(DevOpsScript script) {
        if (script == null || script.getId() == null) {
            logger.warn("Attempted to index null script or script without ID");
            return null;
        }

        try {
            DevOpsScriptDocument document = DevOpsScriptMapper.toDocument(script);
            DevOpsScriptDocument saved = elasticsearchRepository.save(document);
            logger.info("Indexed script with ID: {} in Elasticsearch", script.getId());
            return saved;
        } catch (Exception e) {
            logger.error("Error indexing script with ID: {}", script.getId(), e);
            throw new RuntimeException("Failed to index script: " + script.getId(), e);
        }
    }

    /**
     * Index multiple scripts in Elasticsearch using bulk operation.
     * More efficient than indexing one by one.
     * 
     * @param scripts List of scripts to index
     * @return Number of scripts successfully indexed
     */
    @Transactional(readOnly = true)
    public int bulkIndexScripts(List<DevOpsScript> scripts) {
        if (scripts == null || scripts.isEmpty()) {
            logger.warn("Attempted to bulk index empty or null list of scripts");
            return 0;
        }

        try {
            List<DevOpsScriptDocument> documents = scripts.stream()
                    .filter(script -> script != null && script.getId() != null)
                    .map(DevOpsScriptMapper::toDocument)
                    .collect(Collectors.toList());

            if (documents.isEmpty()) {
                logger.warn("No valid scripts to index after filtering");
                return 0;
            }

            Iterable<DevOpsScriptDocument> savedDocuments = elasticsearchRepository.saveAll(documents);
            
            // Count saved documents
            int count = 0;
            for (DevOpsScriptDocument doc : savedDocuments) {
                count++;
            }

            logger.info("Bulk indexed {} scripts in Elasticsearch", count);
            return count;
        } catch (Exception e) {
            logger.error("Error during bulk indexing of scripts", e);
            throw new RuntimeException("Failed to bulk index scripts", e);
        }
    }

    /**
     * Delete a script from Elasticsearch index.
     * 
     * @param scriptId ID of script to delete from index
     */
    public void deleteFromIndex(Long scriptId) {
        if (scriptId == null) {
            logger.warn("Attempted to delete script from index with null ID");
            return;
        }

        try {
            if (elasticsearchRepository.existsById(scriptId)) {
                elasticsearchRepository.deleteById(scriptId);
                logger.info("Deleted script with ID: {} from Elasticsearch index", scriptId);
            } else {
                logger.warn("Script with ID: {} not found in Elasticsearch index", scriptId);
            }
        } catch (Exception e) {
            logger.error("Error deleting script with ID: {} from index", scriptId, e);
            throw new RuntimeException("Failed to delete script from index: " + scriptId, e);
        }
    }

    /**
     * Reindex all scripts from PostgreSQL to Elasticsearch.
     * Useful for initial setup or after major changes.
     * WARNING: This operation can be time-consuming for large datasets.
     * 
     * @return Number of scripts reindexed
     */
    @Transactional(readOnly = true)
    public int reindexAll() {
        logger.info("Starting full reindex of all scripts");
        
        try {
            // Delete all existing documents in the index
            elasticsearchRepository.deleteAll();
            logger.info("Cleared existing Elasticsearch index");

            // Fetch all scripts from PostgreSQL
            List<DevOpsScript> allScripts = devOpsScriptRepository.findAll();
            logger.info("Found {} scripts in database to reindex", allScripts.size());

            if (allScripts.isEmpty()) {
                logger.warn("No scripts found in database to reindex");
                return 0;
            }

            // Bulk index all scripts
            int indexedCount = bulkIndexScripts(allScripts);
            logger.info("Completed full reindex: {} scripts indexed", indexedCount);
            
            return indexedCount;
        } catch (Exception e) {
            logger.error("Error during full reindex operation", e);
            throw new RuntimeException("Failed to reindex all scripts", e);
        }
    }

    /**
     * Update an existing script in the Elasticsearch index.
     * 
     * @param script Updated script entity
     * @return Updated document
     */
    public DevOpsScriptDocument updateIndex(DevOpsScript script) {
        if (script == null || script.getId() == null) {
            logger.warn("Attempted to update index with null script or script without ID");
            return null;
        }

        try {
            // Check if document exists
            if (!elasticsearchRepository.existsById(script.getId())) {
                logger.warn("Script with ID: {} not found in index, creating new entry", script.getId());
            }

            DevOpsScriptDocument document = DevOpsScriptMapper.toDocument(script);
            DevOpsScriptDocument updated = elasticsearchRepository.save(document);
            logger.info("Updated script with ID: {} in Elasticsearch index", script.getId());
            return updated;
        } catch (Exception e) {
            logger.error("Error updating script with ID: {} in index", script.getId(), e);
            throw new RuntimeException("Failed to update script in index: " + script.getId(), e);
        }
    }

    /**
     * Check if a script is indexed in Elasticsearch.
     * 
     * @param scriptId Script ID to check
     * @return true if script is indexed, false otherwise
     */
    public boolean isIndexed(Long scriptId) {
        if (scriptId == null) {
            return false;
        }

        try {
            return elasticsearchRepository.existsById(scriptId);
        } catch (Exception e) {
            logger.error("Error checking if script with ID: {} is indexed", scriptId, e);
            return false;
        }
    }

    /**
     * Get the total count of indexed scripts.
     * 
     * @return Number of scripts in Elasticsearch index
     */
    public long getIndexedCount() {
        try {
            return elasticsearchRepository.count();
        } catch (Exception e) {
            logger.error("Error getting indexed script count", e);
            return 0;
        }
    }
}
