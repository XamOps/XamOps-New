package com.xammer.cloud.controller;

import com.xammer.cloud.domain.DevOpsScript;
import com.xammer.cloud.repository.DevOpsScriptRepository;
import com.xammer.cloud.service.DevOpsScriptIndexingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST controller for managing Elasticsearch indexing operations.
 * Provides endpoints for indexing, reindexing, and managing the search index.
 * 
 * Requirements: REQ-2.3.1 (Full-Text Search), REQ-2.6.1 (Performance)
 */
@RestController
@RequestMapping("/api/dev_ops_script/index")
@CrossOrigin(origins = "*")
public class DevOpsScriptIndexController {

    @Autowired
    private DevOpsScriptIndexingService indexingService;

    @Autowired
    private DevOpsScriptRepository scriptRepository;

    /**
     * Trigger a full reindex of all scripts from PostgreSQL to Elasticsearch.
     * This endpoint should be used for initial setup or maintenance.
     * 
     * POST /api/dev_ops_script/index/reindex-all
     * 
     * @return Response with reindex statistics
     */
    @PostMapping("/reindex-all")
    public ResponseEntity<Map<String, Object>> reindexAll() {
        try {
            int indexedCount = indexingService.reindexAll();
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Successfully reindexed all scripts");
            response.put("indexedCount", indexedCount);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to reindex scripts: " + e.getMessage());
            errorResponse.put("indexedCount", 0);
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Index a single script by ID.
     * 
     * POST /api/dev_ops_script/index/{scriptId}
     * 
     * @param scriptId ID of the script to index
     * @return Response with indexing result
     */
    @PostMapping("/{scriptId}")
    public ResponseEntity<Map<String, Object>> indexScript(@PathVariable Long scriptId) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            DevOpsScript script = scriptRepository.findById(scriptId).orElse(null);
            
            if (script == null) {
                response.put("success", false);
                response.put("message", "Script not found with ID: " + scriptId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            indexingService.indexScript(script);
            
            response.put("success", true);
            response.put("message", "Script indexed successfully");
            response.put("scriptId", scriptId);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to index script: " + e.getMessage());
            response.put("scriptId", scriptId);
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Delete a script from the Elasticsearch index.
     * 
     * DELETE /api/dev_ops_script/index/{scriptId}
     * 
     * @param scriptId ID of the script to delete from index
     * @return Response with deletion result
     */
    @DeleteMapping("/{scriptId}")
    public ResponseEntity<Map<String, Object>> deleteFromIndex(@PathVariable Long scriptId) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            indexingService.deleteFromIndex(scriptId);
            
            response.put("success", true);
            response.put("message", "Script removed from index successfully");
            response.put("scriptId", scriptId);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to delete script from index: " + e.getMessage());
            response.put("scriptId", scriptId);
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Get index statistics.
     * 
     * GET /api/dev_ops_script/index/stats
     * 
     * @return Index statistics including count and health status
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getIndexStats() {
        Map<String, Object> response = new HashMap<>();
        
        try {
            long indexedCount = indexingService.getIndexedCount();
            long dbCount = scriptRepository.count();
            
            response.put("success", true);
            response.put("indexedCount", indexedCount);
            response.put("databaseCount", dbCount);
            response.put("inSync", indexedCount == dbCount);
            response.put("message", indexedCount == dbCount ? 
                "Index is synchronized with database" : 
                "Index is out of sync with database. Consider running reindex.");
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to retrieve index stats: " + e.getMessage());
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Check if a specific script is indexed.
     * 
     * GET /api/dev_ops_script/index/{scriptId}/status
     * 
     * @param scriptId ID of the script to check
     * @return Indexing status for the script
     */
    @GetMapping("/{scriptId}/status")
    public ResponseEntity<Map<String, Object>> checkIndexStatus(@PathVariable Long scriptId) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            boolean isIndexed = indexingService.isIndexed(scriptId);
            
            response.put("success", true);
            response.put("scriptId", scriptId);
            response.put("isIndexed", isIndexed);
            response.put("message", isIndexed ? 
                "Script is indexed in Elasticsearch" : 
                "Script is not indexed in Elasticsearch");
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to check index status: " + e.getMessage());
            response.put("scriptId", scriptId);
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
}
