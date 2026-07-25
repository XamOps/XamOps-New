package com.xammer.cloud.service.compai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.compai.ControlSummaryDto;
import com.xammer.cloud.dto.compai.OverviewDto;
import com.xammer.cloud.dto.compai.PersonDto;
import com.xammer.cloud.dto.compai.PolicyDto;
import com.xammer.cloud.dto.compai.TaskDto;
import com.xammer.cloud.service.RedisCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompAiServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private CompAiApiClient apiClient;
    private RedisCacheService redis;
    private CompAiService service;

    private JsonNode fixture(String name) throws Exception {
        return mapper.readTree(Files.readString(Path.of("src/test/resources/compai/" + name)));
    }

    @BeforeEach
    void setUp() throws Exception {
        apiClient = mock(CompAiApiClient.class);
        redis = mock(RedisCacheService.class);
        when(redis.get(anyString(), any(com.fasterxml.jackson.core.type.TypeReference.class))).thenReturn(Optional.empty());
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.getJson("/controls?perPage=100")).thenReturn(fixture("controls-list.json"));
        when(apiClient.getJson("/policies?perPage=100")).thenReturn(fixture("policies-list.json"));
        when(apiClient.getJson("/people?perPage=100")).thenReturn(fixture("people-list.json"));
        when(apiClient.getJson("/vendors?perPage=100")).thenReturn(mapper.readTree("{\"data\":[],\"count\":0}"));
        service = new CompAiService(apiClient, redis);
    }

    @Test
    void getControls_derivesCategoryAndStatusFromRealShape() {
        List<ControlSummaryDto> controls = service.getControls();
        assertEquals(3, controls.size());

        ControlSummaryDto passing = controls.get(0);
        assertEquals("Acceptable Use", passing.getName());
        assertEquals("C1", passing.getCategory());               // "C1.1" -> "C1"
        assertEquals("PASSING", passing.getStatus());            // published policy
        assertEquals("Acceptable Use & Workstation Security", passing.getPolicyName());
        assertEquals(1, passing.getTaskCount());

        assertEquals("CC6", controls.get(1).getCategory());      // "CC6.1" -> "CC6"
        assertEquals("NOT_STARTED", controls.get(1).getStatus()); // draft policy + todo task

        assertEquals("IN_PROGRESS", controls.get(2).getStatus()); // needs_review policy / in_progress task
    }

    @Test
    void getPolicies_mapsStatus() {
        List<PolicyDto> policies = service.getPolicies();
        assertEquals(2, policies.size());
        assertEquals("published", policies.get(0).getStatus());
    }

    @Test
    void getTasks_aggregatesFromControlsWithControlName() {
        List<TaskDto> tasks = service.getTasks();
        assertEquals(3, tasks.size());
        assertEquals("Acceptable Use", tasks.get(0).getControlName());
        assertNotNull(tasks.get(0).getTitle());
    }

    @Test
    void getPersonnel_fallsBackToEmailWhenNameBlank() {
        List<PersonDto> people = service.getPersonnel();
        assertEquals(1, people.size());
        assertEquals("Aditya Mehta", people.get(0).getName());
        assertEquals("owner", people.get(0).getRole());
        assertTrue(people.get(0).isActive());
    }

    @Test
    void getOverview_computesReadinessFromDerivedStatuses() {
        OverviewDto o = service.getOverview();
        assertEquals("SOC 2", o.getFrameworkName());
        assertEquals(3, o.getControlsTotal());
        assertEquals(1, o.getControlsPassing());
        assertEquals(1, o.getControlsInProgress());
        assertEquals(1, o.getControlsNotStarted());
        assertEquals(33, o.getReadinessPercent());   // 1/3
        assertEquals(2, o.getPoliciesTotal());
        assertEquals(1, o.getPoliciesPublished());
        assertEquals(1, o.getPeopleTotal());
        assertEquals(0, o.getVendorsTotal());
    }
}
