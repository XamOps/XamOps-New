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
        assertEquals("Establish an Acceptable Use Policy for Information and Assets", passing.getDescription());
        assertEquals(1, passing.getRequirements().size());
        assertEquals("C1.1", passing.getRequirements().get(0).getIdentifier());
        assertEquals(1, passing.getPolicies().size());
        assertEquals("pol_1", passing.getPolicies().get(0).getId());
        assertEquals("published", passing.getPolicies().get(0).getStatus());
        assertEquals(1, passing.getTasks().size());
        assertEquals("Separation of Environments", passing.getTasks().get(0).getTitle());
        assertEquals("done", passing.getTasks().get(0).getStatus());

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
    void getPolicies_extractsPlainTextFromTiptapContent() throws Exception {
        when(apiClient.getJson("/policies?perPage=100")).thenReturn(mapper.readTree(
                "{\"data\":[{\"id\":\"pol_9\",\"name\":\"Access Control Policy\",\"description\":\"d\",\"status\":\"draft\","
                        + "\"content\":[{\"type\":\"doc\",\"content\":["
                        + "{\"type\":\"heading\",\"content\":[{\"type\":\"text\",\"text\":\"Purpose\"}]},"
                        + "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Restrict access to \"},"
                        + "{\"type\":\"text\",\"text\":\"authorized users only.\"}]}"
                        + "]}]}]}"));

        PolicyDto policy = service.getPolicies().get(0);

        assertEquals("Purpose", policy.getContent().split("\n\n")[0].trim());
        assertTrue(policy.getContent().contains("Restrict access to authorized users only."));
    }

    @Test
    void getPolicies_contentIsNullWhenNoContentField() {
        PolicyDto policy = service.getPolicies().get(0); // fixture policies have no "content" field
        assertNull(policy.getContent());
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

    @Test
    void publishPolicy_callsPublishEndpointAndEvictsCaches() throws Exception {
        when(apiClient.postJson("/policies/pol_1/versions/publish", java.util.Map.of()))
                .thenReturn(mapper.readTree("{\"id\":\"pol_1\",\"status\":\"published\"}"));
        // getPolicies() re-fetch after publish
        when(apiClient.getJson("/policies?perPage=100")).thenReturn(fixture("policies-list.json"));

        var result = service.publishPolicy("pol_1");

        org.mockito.Mockito.verify(apiClient).postJson("/policies/pol_1/versions/publish", java.util.Map.of());
        org.mockito.Mockito.verify(redis).evict("compai:policies");
        org.mockito.Mockito.verify(redis).evict("compai:controls");
        assertNotNull(result);
        assertEquals("pol_1", result.getId());
    }

    @Test
    void updateTask_statusOnly_callsPatchEndpointAndEvictsCaches() throws Exception {
        com.xammer.cloud.dto.compai.UpdateTaskRequestDto request =
                new com.xammer.cloud.dto.compai.UpdateTaskRequestDto(null, null, null, "done");
        when(apiClient.patchJson("/tasks/tsk_1", request))
                .thenReturn(mapper.readTree("{\"id\":\"tsk_1\",\"status\":\"done\"}"));
        when(apiClient.getJson("/controls?perPage=100")).thenReturn(fixture("controls-list.json"));

        var result = service.updateTask("tsk_1", request);

        org.mockito.Mockito.verify(apiClient).patchJson("/tasks/tsk_1", request);
        org.mockito.Mockito.verify(redis).evict("compai:tasks");
        org.mockito.Mockito.verify(redis).evict("compai:controls");
        assertNotNull(result);
    }

    @Test
    void createVendor_postsPayloadAndEvictsCache() throws Exception {
        com.xammer.cloud.dto.compai.CreateVendorRequestDto request =
                new com.xammer.cloud.dto.compai.CreateVendorRequestDto("AWS", "Cloud host", "cloud", "assessed");
        when(apiClient.postJson(org.mockito.ArgumentMatchers.eq("/vendors"), org.mockito.ArgumentMatchers.eq(request)))
                .thenReturn(mapper.readTree("{\"id\":\"ven_1\",\"name\":\"AWS\",\"category\":\"cloud\",\"status\":\"assessed\",\"description\":\"Cloud host\"}"));

        var result = service.createVendor(request);

        org.mockito.Mockito.verify(redis).evict("compai:vendors");
        assertEquals("ven_1", result.getId());
        assertEquals("AWS", result.getName());
    }

    @Test
    void createRisk_postsPayloadAndEvictsCache() throws Exception {
        com.xammer.cloud.dto.compai.CreateRiskRequestDto request =
                new com.xammer.cloud.dto.compai.CreateRiskRequestDto("Offboarding gap", "desc", "people", "hr");
        when(apiClient.postJson(org.mockito.ArgumentMatchers.eq("/risks"), org.mockito.ArgumentMatchers.eq(request)))
                .thenReturn(mapper.readTree("{\"id\":\"risk_1\",\"title\":\"Offboarding gap\",\"description\":\"desc\",\"category\":\"people\"}"));

        var result = service.createRisk(request);

        org.mockito.Mockito.verify(redis).evict("compai:risks");
        assertEquals("risk_1", result.getId());
        assertEquals("Offboarding gap", result.getTitle());
    }

    @Test
    void updateVendor_patchesPayloadAndEvictsCache() throws Exception {
        com.xammer.cloud.dto.compai.UpdateVendorRequestDto request =
                new com.xammer.cloud.dto.compai.UpdateVendorRequestDto("AWS", null, null, "in_progress");
        when(apiClient.patchJson(org.mockito.ArgumentMatchers.eq("/vendors/ven_1"), org.mockito.ArgumentMatchers.eq(request)))
                .thenReturn(mapper.readTree("{\"id\":\"ven_1\",\"name\":\"AWS\",\"status\":\"in_progress\"}"));

        var result = service.updateVendor("ven_1", request);

        org.mockito.Mockito.verify(redis).evict("compai:vendors");
        assertEquals("in_progress", result.getStatus());
    }

    @Test
    void deleteVendor_callsDeleteEndpointAndEvictsCache() {
        service.deleteVendor("ven_1");

        org.mockito.Mockito.verify(apiClient).deleteJson("/vendors/ven_1");
        org.mockito.Mockito.verify(redis).evict("compai:vendors");
    }

    @Test
    void updateRisk_patchesPayloadAndEvictsCache() throws Exception {
        com.xammer.cloud.dto.compai.UpdateRiskRequestDto request =
                new com.xammer.cloud.dto.compai.UpdateRiskRequestDto("Updated title", null, null, null);
        when(apiClient.patchJson(org.mockito.ArgumentMatchers.eq("/risks/risk_1"), org.mockito.ArgumentMatchers.eq(request)))
                .thenReturn(mapper.readTree("{\"id\":\"risk_1\",\"title\":\"Updated title\"}"));

        var result = service.updateRisk("risk_1", request);

        org.mockito.Mockito.verify(redis).evict("compai:risks");
        assertEquals("Updated title", result.getTitle());
    }

    @Test
    void deleteRisk_callsDeleteEndpointAndEvictsCache() {
        service.deleteRisk("risk_1");

        org.mockito.Mockito.verify(apiClient).deleteJson("/risks/risk_1");
        org.mockito.Mockito.verify(redis).evict("compai:risks");
    }

    @Test
    void deleteControl_callsDeleteEndpointAndEvictsControlAndTaskCaches() {
        service.deleteControl("ctl_1");

        org.mockito.Mockito.verify(apiClient).deleteJson("/controls/ctl_1");
        org.mockito.Mockito.verify(redis).evict("compai:controls");
        org.mockito.Mockito.verify(redis).evict("compai:tasks");
    }

    @Test
    void setPolicyArchived_patchesIsArchivedAndEvictsCaches() throws Exception {
        when(apiClient.patchJson("/policies/pol_1", java.util.Map.of("isArchived", true)))
                .thenReturn(mapper.readTree("{\"id\":\"pol_1\",\"status\":\"draft\"}"));

        var result = service.setPolicyArchived("pol_1", true);

        org.mockito.Mockito.verify(redis).evict("compai:policies");
        org.mockito.Mockito.verify(redis).evict("compai:controls");
        assertEquals("pol_1", result.getId());
    }

    @Test
    void createTask_postsPayloadAndEvictsCaches() throws Exception {
        com.xammer.cloud.dto.compai.CreateTaskRequestDto request =
                new com.xammer.cloud.dto.compai.CreateTaskRequestDto("Review logs", "desc", "mem_1", "it", java.util.List.of("ctl_1"));
        when(apiClient.postJson(org.mockito.ArgumentMatchers.eq("/tasks"), org.mockito.ArgumentMatchers.eq(request)))
                .thenReturn(mapper.readTree("{\"id\":\"tsk_9\",\"title\":\"Review logs\",\"status\":\"todo\"}"));

        var result = service.createTask(request);

        org.mockito.Mockito.verify(redis).evict("compai:tasks");
        org.mockito.Mockito.verify(redis).evict("compai:controls");
        assertEquals("tsk_9", result.getId());
        assertEquals("Review logs", result.getTitle());
    }

    @Test
    void inviteMember_postsInvitesArrayWrapperAndEvictsCache() {
        com.xammer.cloud.dto.compai.InviteMemberRequestDto request =
                new com.xammer.cloud.dto.compai.InviteMemberRequestDto("new@xammer.com", "employee");

        service.inviteMember(request);

        org.mockito.Mockito.verify(apiClient).postJson(
                org.mockito.ArgumentMatchers.eq("/people/invite"),
                org.mockito.ArgumentMatchers.eq(java.util.Map.of("invites", java.util.List.of(
                        java.util.Map.of("email", "new@xammer.com", "roles", java.util.List.of("employee"))))));
        org.mockito.Mockito.verify(redis).evict("compai:people");
    }

    @Test
    void updatePerson_patchesPayloadAndEvictsCache() throws Exception {
        com.xammer.cloud.dto.compai.UpdatePersonRequestDto request =
                new com.xammer.cloud.dto.compai.UpdatePersonRequestDto("admin", null, null);
        when(apiClient.patchJson(org.mockito.ArgumentMatchers.eq("/people/mem_1"), org.mockito.ArgumentMatchers.eq(request)))
                .thenReturn(mapper.readTree("{}"));
        when(apiClient.getJson("/people?perPage=100")).thenReturn(fixture("people-list.json"));

        var result = service.updatePerson("mem_1", request);

        org.mockito.Mockito.verify(redis).evict("compai:people");
        assertNotNull(result);
    }

    @Test
    void deletePerson_callsDeleteEndpointAndEvictsCache() {
        service.deletePerson("mem_1");

        org.mockito.Mockito.verify(apiClient).deleteJson("/people/mem_1");
        org.mockito.Mockito.verify(redis).evict("compai:people");
    }
}
