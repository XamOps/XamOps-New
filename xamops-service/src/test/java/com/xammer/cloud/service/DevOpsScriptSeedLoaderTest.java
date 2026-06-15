package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.DevOpsScript;
import com.xammer.cloud.domain.ScriptParameter;
import com.xammer.cloud.repository.DevOpsScriptRepository;
import com.xammer.cloud.repository.ScriptParameterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DevOpsScriptSeedLoader}. These exercise manifest parsing and
 * idempotency against the real seed manifests on the classpath, with all database
 * interactions mocked (no live DB required).
 *
 * Requirements: REQ-2.1.1, REQ-2.1.2, REQ-2.1.3, REQ-2.3.1
 */
@ExtendWith(MockitoExtension.class)
class DevOpsScriptSeedLoaderTest {

    private static final String BASH_MANIFEST = "data/seed/bash_scripts_manifest.json";
    private static final String TERRAFORM_MANIFEST = "data/seed/terraform_scripts_manifest.json";

    @Mock
    private DevOpsScriptRepository scriptRepository;
    @Mock
    private ScriptParameterRepository parameterRepository;
    @Mock
    private DataSource dataSource;

    private DevOpsScriptSeedLoader loader;

    @BeforeEach
    void setUp() {
        loader = new DevOpsScriptSeedLoader(scriptRepository, parameterRepository,
                dataSource, new ObjectMapper());
    }

    @Test
    void loadFullManifest_importsNewScriptsWithCodeAndParameters() {
        // No script exists yet -> everything is imported.
        when(scriptRepository.existsByName(anyString())).thenReturn(false);
        when(parameterRepository.findByScriptIdOrderByDisplayOrderAsc(anyLong()))
                .thenReturn(Collections.emptyList());

        // Assign ids on save so parameters can be linked.
        AtomicLong ids = new AtomicLong(0);
        when(scriptRepository.save(any(DevOpsScript.class))).thenAnswer(inv -> {
            DevOpsScript s = inv.getArgument(0);
            s.setId(ids.incrementAndGet());
            return s;
        });

        int imported = loader.loadFullManifest(BASH_MANIFEST);

        assertEquals(26, imported, "All 26 bash scripts should be imported");

        // Verify the persisted scripts carry the expected defaults and loaded code.
        verify(scriptRepository, times(26)).save(argThat(s ->
                "bash".equals(s.getCategory())
                        && "XamOps Team".equals(s.getAuthor())
                        && "published".equals(s.getStatus())
                        && s.getCode() != null && !s.getCode().isBlank()
                        && s.getVersion() != null));
        // Parameters were persisted for the scripts.
        verify(parameterRepository, atLeastOnce()).saveAll(any());
    }

    @Test
    void loadFullManifest_isIdempotent_skipsExistingScripts() {
        // Every script already exists -> nothing imported.
        when(scriptRepository.existsByName(anyString())).thenReturn(true);

        int imported = loader.loadFullManifest(BASH_MANIFEST);

        assertEquals(0, imported, "Existing scripts must be skipped");
        verify(scriptRepository, never()).save(any(DevOpsScript.class));
        verify(parameterRepository, never()).saveAll(any());
    }

    @Test
    void applyParameterOnlyManifest_upsertsParametersForMatchedScripts() {
        // Match every script by name to a stub entity with an id.
        AtomicLong ids = new AtomicLong(0);
        when(scriptRepository.findFirstByName(anyString())).thenAnswer(inv -> {
            DevOpsScript s = new DevOpsScript();
            s.setId(ids.incrementAndGet());
            s.setName(inv.getArgument(0));
            return Optional.of(s);
        });
        when(parameterRepository.findByScriptIdOrderByDisplayOrderAsc(anyLong()))
                .thenReturn(Collections.emptyList());

        int upserted = loader.applyParameterOnlyManifest(TERRAFORM_MANIFEST);

        // The terraform manifest defines 221 parameters across 32 scripts.
        assertEquals(221, upserted, "All Terraform parameters should be upserted");
        verify(parameterRepository, times(32)).saveAll(any());
    }

    @Test
    void applyParameterOnlyManifest_skipsUnknownScripts() {
        // No matching DevOpsScript rows exist.
        when(scriptRepository.findFirstByName(anyString())).thenReturn(Optional.empty());

        int upserted = loader.applyParameterOnlyManifest(TERRAFORM_MANIFEST);

        assertEquals(0, upserted, "Unknown scripts contribute no parameters");
        verify(parameterRepository, never()).saveAll(any());
    }

    @Test
    void applyParameterOnlyManifest_updatesExistingParameterInPlace() {
        // One existing parameter is reused (updated) rather than duplicated.
        when(scriptRepository.findFirstByName(anyString())).thenAnswer(inv -> {
            DevOpsScript s = new DevOpsScript();
            s.setId(1L);
            s.setName(inv.getArgument(0));
            return Optional.of(s);
        });
        ScriptParameter existing = new ScriptParameter();
        existing.setId(99L);
        existing.setName("REGION");
        when(parameterRepository.findByScriptIdOrderByDisplayOrderAsc(anyLong()))
                .thenReturn(List.of(existing));

        loader.applyParameterOnlyManifest(TERRAFORM_MANIFEST);

        // The reused REGION parameter keeps its primary key (update, not insert).
        verify(parameterRepository, atLeastOnce()).saveAll(argThat(iterable -> {
            for (Object o : iterable) {
                ScriptParameter p = (ScriptParameter) o;
                if ("REGION".equals(p.getName()) && Long.valueOf(99L).equals(p.getId())) {
                    return true;
                }
            }
            return false;
        }));
    }
}
