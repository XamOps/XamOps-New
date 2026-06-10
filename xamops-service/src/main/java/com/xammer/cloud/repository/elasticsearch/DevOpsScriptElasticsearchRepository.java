package com.xammer.cloud.repository.elasticsearch;

import com.xammer.cloud.domain.elasticsearch.DevOpsScriptDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.annotations.Query;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Elasticsearch repository for DevOps scripts.
 * Provides full-text search capabilities with advanced querying.
 * 
 * Requirements: REQ-2.3.1 (Full-Text Search), REQ-2.6.1 (Performance)
 */
@Repository
public interface DevOpsScriptElasticsearchRepository extends ElasticsearchRepository<DevOpsScriptDocument, Long> {

    /**
     * Search scripts by name, description, or tags with fuzzy matching.
     * Uses multi-match query for better relevance scoring.
     * 
     * @param name Search term for name field
     * @param description Search term for description field
     * @param tags Search term for tags field
     * @param pageable Pagination parameters
     * @return Page of matching scripts
     */
    @Query("{\"bool\": {\"should\": [{\"multi_match\": {\"query\": \"?0\", \"fields\": [\"name^3\", \"description^2\", \"tags^2\"], \"fuzziness\": \"AUTO\"}}]}}")
    Page<DevOpsScriptDocument> searchByMultipleFields(String name, String description, String tags, Pageable pageable);

    /**
     * Find scripts by category.
     * 
     * @param category Script category (terraform, bash, python, ansible)
     * @param pageable Pagination parameters
     * @return Page of scripts in the specified category
     */
    Page<DevOpsScriptDocument> findByCategory(String category, Pageable pageable);

    /**
     * Find scripts by cloud provider.
     * 
     * @param cloudProvider Cloud provider (aws, azure, gcp, multi-cloud)
     * @param pageable Pagination parameters
     * @return Page of scripts for the specified cloud provider
     */
    Page<DevOpsScriptDocument> findByCloudProvidersContaining(String cloudProvider, Pageable pageable);

    /**
     * Find scripts by difficulty level.
     * 
     * @param difficulty Difficulty level (beginner, intermediate, advanced)
     * @param pageable Pagination parameters
     * @return Page of scripts with the specified difficulty
     */
    Page<DevOpsScriptDocument> findByDifficulty(String difficulty, Pageable pageable);

    /**
     * Find scripts by status.
     * 
     * @param status Script status (published, draft, deprecated)
     * @param pageable Pagination parameters
     * @return Page of scripts with the specified status
     */
    Page<DevOpsScriptDocument> findByStatus(String status, Pageable pageable);

    /**
     * Find scripts by tags.
     * 
     * @param tag Tag to search for
     * @param pageable Pagination parameters
     * @return Page of scripts containing the specified tag
     */
    Page<DevOpsScriptDocument> findByTagsContaining(String tag, Pageable pageable);

    /**
     * Find featured scripts.
     * 
     * @param isFeatured Featured flag
     * @param pageable Pagination parameters
     * @return Page of featured scripts
     */
    Page<DevOpsScriptDocument> findByIsFeatured(Boolean isFeatured, Pageable pageable);

    /**
     * Find scripts by minimum average rating.
     * 
     * @param minRating Minimum rating threshold
     * @param pageable Pagination parameters
     * @return Page of scripts with rating >= minRating
     */
    Page<DevOpsScriptDocument> findByAverageRatingGreaterThanEqual(Double minRating, Pageable pageable);

    /**
     * Find scripts by category and cloud provider.
     * 
     * @param category Script category
     * @param cloudProvider Cloud provider
     * @param pageable Pagination parameters
     * @return Page of matching scripts
     */
    Page<DevOpsScriptDocument> findByCategoryAndCloudProvidersContaining(String category, String cloudProvider, Pageable pageable);

    /**
     * Find scripts by category and difficulty.
     * 
     * @param category Script category
     * @param difficulty Difficulty level
     * @param pageable Pagination parameters
     * @return Page of matching scripts
     */
    Page<DevOpsScriptDocument> findByCategoryAndDifficulty(String category, String difficulty, Pageable pageable);
}
