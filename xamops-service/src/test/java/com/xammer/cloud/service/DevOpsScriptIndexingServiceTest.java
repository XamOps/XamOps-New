package com.xammer.cloud.service;

import com.xammer.cloud.domain.DevOpsScript;
import com.xammer.cloud.domain.elasticsearch.DevOpsScriptDocument;
import com.xammer.cloud.repository.DevOpsScriptRepository;
import com.xammer.cloud.repository.elasticsearch.DevOpsScriptElasticsearchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DevOpsScriptIndexingService.
 * Tests indexing operations without requiring a running Elasticsearch instance.
 * 
 * Requirements: REQ-2.3.1 (Full-Text Search), REQ-2.6.1 (Performance)
 */
@ExtendWith(MockitoExtension.class)
class DevOpsScriptIndexingServiceTest {

    @Mock
    private DevOpsScriptElasticsearchRepository elasticsearchRepository;

    @Mock
    private DevOpsScriptRepository devOpsScriptRepository;

    @InjectMocks
    private DevOpsScriptIndexingService indexingService;

    private DevOpsScript testScript;
    private DevOpsScriptDocument testDocument;

    @BeforeEach
    void setUp() {
        // Create test script
        testScript = new DevOpsScript();
        testScript.setId(1L);
        testScript.setName("Test Terraform Script");
        testScript.setDescription("A test script for unit testing");
        testScript.setCategory("terraform");
        testScript.setCode("resource \"aws_s3_bucket\" \"test\" {}");
        testScript.setVersion("1.0.0");
        testScript.setStatus("published");

        // Create test document
        testDocument = new DevOpsScriptDocument();
        testDocument.setId(1L);
        testDocument.setName("Test Terraform Script");
        testDocument.setDescription("A test script for unit testing");
        testDocument.setCategory("terraform");
    }

    @Test
    void testIndexScript_Success() {
        // Arrange
        when(elasticsearchRepository.save(any(DevOpsScriptDocument.class))).thenReturn(testDocument);

        // Act
        DevOpsScriptDocument result = indexingService.indexScript(testScript);

        // Assert
        assertNotNull(result);
        assertEquals(testScript.getId(), result.getId());
        assertEquals(testScript.getName(), result.getName());
        verify(elasticsearchRepository, times(1)).save(any(DevOpsScriptDocument.class));
    }

    @Test
    void testIndexScript_NullScript() {
        // Act
        DevOpsScriptDocument result = indexingService.indexScript(null);

        // Assert
        assertNull(result);
        verify(elasticsearchRepository, never()).save(any(DevOpsScriptDocument.class));
    }

    @Test
    void testIndexScript_ScriptWithoutId() {
        // Arrange
        DevOpsScript scriptWithoutId = new DevOpsScript();
        scriptWithoutId.setName("Test Script");

        // Act
        DevOpsScriptDocument result = indexingService.indexScript(scriptWithoutId);

        // Assert
        assertNull(result);
        verify(elasticsearchRepository, never()).save(any(DevOpsScriptDocument.class));
    }

    @Test
    void testBulkIndexScripts_Success() {
        // Arrange
        DevOpsScript script2 = new DevOpsScript();
        script2.setId(2L);
        script2.setName("Script 2");
        script2.setCategory("bash");

        List<DevOpsScript> scripts = Arrays.asList(testScript, script2);
        
        DevOpsScriptDocument doc2 = new DevOpsScriptDocument();
        doc2.setId(2L);
        doc2.setName("Script 2");
        
        when(elasticsearchRepository.saveAll(anyList()))
                .thenReturn(Arrays.asList(testDocument, doc2));

        // Act
        int result = indexingService.bulkIndexScripts(scripts);

        // Assert
        assertEquals(2, result);
        verify(elasticsearchRepository, times(1)).saveAll(anyList());
    }

    @Test
    void testBulkIndexScripts_EmptyList() {
        // Act
        int result = indexingService.bulkIndexScripts(Arrays.asList());

        // Assert
        assertEquals(0, result);
        verify(elasticsearchRepository, never()).saveAll(anyList());
    }

    @Test
    void testBulkIndexScripts_NullList() {
        // Act
        int result = indexingService.bulkIndexScripts(null);

        // Assert
        assertEquals(0, result);
        verify(elasticsearchRepository, never()).saveAll(anyList());
    }

    @Test
    void testDeleteFromIndex_Success() {
        // Arrange
        when(elasticsearchRepository.existsById(1L)).thenReturn(true);

        // Act
        indexingService.deleteFromIndex(1L);

        // Assert
        verify(elasticsearchRepository, times(1)).existsById(1L);
        verify(elasticsearchRepository, times(1)).deleteById(1L);
    }

    @Test
    void testDeleteFromIndex_NotFound() {
        // Arrange
        when(elasticsearchRepository.existsById(1L)).thenReturn(false);

        // Act
        indexingService.deleteFromIndex(1L);

        // Assert
        verify(elasticsearchRepository, times(1)).existsById(1L);
        verify(elasticsearchRepository, never()).deleteById(1L);
    }

    @Test
    void testDeleteFromIndex_NullId() {
        // Act
        indexingService.deleteFromIndex(null);

        // Assert
        verify(elasticsearchRepository, never()).existsById(anyLong());
        verify(elasticsearchRepository, never()).deleteById(anyLong());
    }

    @Test
    void testUpdateIndex_Success() {
        // Arrange
        when(elasticsearchRepository.existsById(1L)).thenReturn(true);
        when(elasticsearchRepository.save(any(DevOpsScriptDocument.class))).thenReturn(testDocument);

        // Act
        DevOpsScriptDocument result = indexingService.updateIndex(testScript);

        // Assert
        assertNotNull(result);
        assertEquals(testScript.getId(), result.getId());
        verify(elasticsearchRepository, times(1)).existsById(1L);
        verify(elasticsearchRepository, times(1)).save(any(DevOpsScriptDocument.class));
    }

    @Test
    void testUpdateIndex_NotFound() {
        // Arrange
        when(elasticsearchRepository.existsById(1L)).thenReturn(false);
        when(elasticsearchRepository.save(any(DevOpsScriptDocument.class))).thenReturn(testDocument);

        // Act
        DevOpsScriptDocument result = indexingService.updateIndex(testScript);

        // Assert
        assertNotNull(result);
        verify(elasticsearchRepository, times(1)).existsById(1L);
        verify(elasticsearchRepository, times(1)).save(any(DevOpsScriptDocument.class));
    }

    @Test
    void testIsIndexed_True() {
        // Arrange
        when(elasticsearchRepository.existsById(1L)).thenReturn(true);

        // Act
        boolean result = indexingService.isIndexed(1L);

        // Assert
        assertTrue(result);
        verify(elasticsearchRepository, times(1)).existsById(1L);
    }

    @Test
    void testIsIndexed_False() {
        // Arrange
        when(elasticsearchRepository.existsById(1L)).thenReturn(false);

        // Act
        boolean result = indexingService.isIndexed(1L);

        // Assert
        assertFalse(result);
        verify(elasticsearchRepository, times(1)).existsById(1L);
    }

    @Test
    void testIsIndexed_NullId() {
        // Act
        boolean result = indexingService.isIndexed(null);

        // Assert
        assertFalse(result);
        verify(elasticsearchRepository, never()).existsById(anyLong());
    }

    @Test
    void testGetIndexedCount() {
        // Arrange
        when(elasticsearchRepository.count()).thenReturn(42L);

        // Act
        long result = indexingService.getIndexedCount();

        // Assert
        assertEquals(42L, result);
        verify(elasticsearchRepository, times(1)).count();
    }

    @Test
    void testReindexAll_Success() {
        // Arrange
        DevOpsScript script2 = new DevOpsScript();
        script2.setId(2L);
        script2.setName("Script 2");
        script2.setCategory("python");

        List<DevOpsScript> allScripts = Arrays.asList(testScript, script2);
        
        when(devOpsScriptRepository.findAll()).thenReturn(allScripts);
        when(elasticsearchRepository.saveAll(anyList()))
                .thenReturn(Arrays.asList(testDocument, new DevOpsScriptDocument()));

        // Act
        int result = indexingService.reindexAll();

        // Assert
        assertEquals(2, result);
        verify(elasticsearchRepository, times(1)).deleteAll();
        verify(devOpsScriptRepository, times(1)).findAll();
        verify(elasticsearchRepository, times(1)).saveAll(anyList());
    }

    @Test
    void testReindexAll_EmptyDatabase() {
        // Arrange
        when(devOpsScriptRepository.findAll()).thenReturn(Arrays.asList());

        // Act
        int result = indexingService.reindexAll();

        // Assert
        assertEquals(0, result);
        verify(elasticsearchRepository, times(1)).deleteAll();
        verify(devOpsScriptRepository, times(1)).findAll();
        verify(elasticsearchRepository, never()).saveAll(anyList());
    }
}
