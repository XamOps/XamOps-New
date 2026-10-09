package com.xammer.cloud.service.gcp;

import com.google.cloud.compute.v1.DistributionPolicy;
import com.google.cloud.compute.v1.DistributionPolicyZoneConfiguration;
import com.google.cloud.compute.v1.InstanceGroupManager;
import com.google.cloud.compute.v1.InstanceGroupManagersClient;
import com.google.cloud.compute.v1.InstanceGroupManagersScopedList;
import com.google.cloud.compute.v1.InstanceProperties;
import com.google.cloud.compute.v1.InstanceTemplate;
import com.google.cloud.compute.v1.InstanceTemplatesClient;
import com.google.cloud.compute.v1.RegionInstanceGroupManagersClient;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.SpotOptimizationEvent;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DailySpotSavingsRepository;
import com.xammer.cloud.repository.SpotOptimizationEventRepository;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.service.autospotting.AutoSpottingBlockedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * GKE node-pool guard (GCP-01) and the safe Disable ordering: DISABLED is recorded only after the
 * rollout PATCH completed. GCP clients and the REST service are mocked; nothing reaches GCP.
 */
class GcpAutoSpottingServiceGuardTest {

    private static final String PROJECT = "proj";
    private static final String REGION = "us-central1";
    private static final String GKE_MIG = "gke-c1-default-pool-abc-grp";
    private static final String TEMPLATE_BASE = "https://www.googleapis.com/compute/v1/projects/proj/global/instanceTemplates/";

    private GcpClientProvider gcpClientProvider;
    private SpotOptimizationEventRepository eventRepository;
    private GcpRestTemplateService restTemplateService;
    private RegionInstanceGroupManagersClient regionIgmClient;
    private GcpPricingService pricingService;
    private GcpAutoSpottingService service;

    @BeforeEach
    void setUp() {
        gcpClientProvider = mock(GcpClientProvider.class);
        CloudAccountRepository cloudAccountRepository = mock(CloudAccountRepository.class);
        eventRepository = mock(SpotOptimizationEventRepository.class);
        restTemplateService = mock(GcpRestTemplateService.class);
        regionIgmClient = mock(RegionInstanceGroupManagersClient.class);
        pricingService = mock(GcpPricingService.class);

        CloudAccount account = new CloudAccount();
        account.setId(3L);
        account.setProvider("GCP");
        account.setGcpProjectId(PROJECT);
        when(cloudAccountRepository.findByProviderAccountId(PROJECT)).thenReturn(Optional.of(account));
        when(gcpClientProvider.getRegionInstanceGroupManagersClient(PROJECT)).thenReturn(Optional.of(regionIgmClient));

        service = new GcpAutoSpottingService(gcpClientProvider, cloudAccountRepository, eventRepository,
                mock(DailySpotSavingsRepository.class), pricingService, restTemplateService,
                mock(RedisCacheService.class));
    }

    private void givenMig(String name, String template) {
        DistributionPolicy.Builder zones = DistributionPolicy.newBuilder();
        for (String z : new String[] {"a", "b", "c"}) {
            zones.addZones(DistributionPolicyZoneConfiguration.newBuilder()
                    .setZone("https://www.googleapis.com/compute/v1/projects/proj/zones/us-central1-" + z));
        }
        when(regionIgmClient.get(PROJECT, REGION, name)).thenReturn(InstanceGroupManager.newBuilder()
                .setName(name)
                .setInstanceTemplate(TEMPLATE_BASE + template)
                .setTargetSize(3)
                .setDistributionPolicy(zones)
                .build());
    }

    private static JsonObject templateJson(String provisioningModel, String description, String labelsJson) {
        String json = "{'name':'tpl','description':'" + description + "','properties':{"
                + "'machineType':'e2-standard-4',"
                + (labelsJson == null ? "" : "'labels':" + labelsJson + ",")
                + "'scheduling':{'provisioningModel':'" + provisioningModel + "'}}}";
        return JsonParser.parseString(json.replace('\'', '"')).getAsJsonObject();
    }

    // ── enable ───────────────────────────────────────────────────────────────

    @Test
    void enable_onGkeNamedMig_isRefusedBeforeAnyRestCall() {
        givenMig(GKE_MIG, "gke-tpl");

        assertThatThrownBy(() -> service.enableAutoSpotting(PROJECT, REGION, GKE_MIG))
                .isInstanceOfSatisfying(AutoSpottingBlockedException.class, e ->
                        assertThat(e.getCode()).isEqualTo(AutoSpottingBlockedException.GKE_MANAGED));

        verifyNoInteractions(restTemplateService);
        verify(gcpClientProvider, never()).getInstanceTemplatesClient(anyString());
        verifyNoInteractions(eventRepository);
    }

    @Test
    void enable_onGkeLabelledTemplate_isRefusedBeforeAnyRestCall() {
        givenMig("web-mig", "web-tpl");
        InstanceTemplatesClient templatesClient = mock(InstanceTemplatesClient.class);
        when(gcpClientProvider.getInstanceTemplatesClient(PROJECT)).thenReturn(Optional.of(templatesClient));
        when(templatesClient.get(PROJECT, "web-tpl")).thenReturn(InstanceTemplate.newBuilder()
                .setName("web-tpl")
                .setProperties(InstanceProperties.newBuilder().putLabels("goog-gke-node", ""))
                .build());

        assertThatThrownBy(() -> service.enableAutoSpotting(PROJECT, REGION, "web-mig"))
                .isInstanceOfSatisfying(AutoSpottingBlockedException.class, e ->
                        assertThat(e.getCode()).isEqualTo(AutoSpottingBlockedException.GKE_MANAGED));

        verifyNoInteractions(restTemplateService);
        verifyNoInteractions(eventRepository);
    }

    // ── disable ──────────────────────────────────────────────────────────────

    @Test
    void disable_onNativeGkeSpotPool_isRefusedAndChangesNothing() throws Exception {
        givenMig(GKE_MIG, "gke-tpl");
        when(restTemplateService.getInstanceTemplate(PROJECT, null, "gke-tpl"))
                .thenReturn(templateJson("SPOT", "GKE node pool template", "{'goog-gke-node':''}"));

        assertThatThrownBy(() -> service.disableAutoSpotting(PROJECT, REGION, GKE_MIG))
                .isInstanceOfSatisfying(AutoSpottingBlockedException.class, e ->
                        assertThat(e.getCode()).isEqualTo(AutoSpottingBlockedException.GKE_MANAGED));

        verify(restTemplateService, never()).createSpotTemplate(any(), any(), any(), any(), anyBoolean());
        verify(restTemplateService, never()).startRollingUpdate(any(), any(), any(), any(), anyInt());
        verifyNoInteractions(eventRepository);
    }

    @Test
    void disable_onLabelOnlyGkeSpotPool_withoutMarker_isRefused() throws Exception {
        givenMig("web-mig", "web-tpl");
        when(restTemplateService.getInstanceTemplate(PROJECT, null, "web-tpl"))
                .thenReturn(templateJson("SPOT", "customer template", "{'goog-gke-node':''}"));

        assertThatThrownBy(() -> service.disableAutoSpotting(PROJECT, REGION, "web-mig"))
                .isInstanceOfSatisfying(AutoSpottingBlockedException.class, e ->
                        assertThat(e.getCode()).isEqualTo(AutoSpottingBlockedException.GKE_MANAGED));

        verify(restTemplateService, never()).createSpotTemplate(any(), any(), any(), any(), anyBoolean());
        verifyNoInteractions(eventRepository);
    }

    @Test
    void disable_onXamOpsConvertedGkePool_revertsAndRecordsDisabledAfterRollout() throws Exception {
        givenMig(GKE_MIG, "gke-tpl-spot-1");
        JsonObject source = templateJson("SPOT", "Spot template created by XamOps AutoSpotting", null);
        when(restTemplateService.getInstanceTemplate(PROJECT, null, "gke-tpl-spot-1")).thenReturn(source);

        service.disableAutoSpotting(PROJECT, REGION, GKE_MIG);

        ArgumentCaptor<String> newName = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SpotOptimizationEvent> event = ArgumentCaptor.forClass(SpotOptimizationEvent.class);
        InOrder order = inOrder(restTemplateService, eventRepository);
        order.verify(restTemplateService).getInstanceTemplate(PROJECT, null, "gke-tpl-spot-1");
        order.verify(restTemplateService).createSpotTemplate(eq(PROJECT), isNull(), newName.capture(),
                same(source), eq(false));
        order.verify(restTemplateService).startRollingUpdate(eq(PROJECT), eq(REGION), eq(GKE_MIG),
                eq(TEMPLATE_BASE + newName.getValue()), eq(3));
        order.verify(eventRepository).save(event.capture());
        order.verifyNoMoreInteractions();

        assertThat(newName.getValue()).contains("-od-");
        assertThat(event.getValue().getStatus()).isEqualTo("DISABLED");
        assertThat(event.getValue().getAsgName()).isEqualTo(GKE_MIG);
        assertThat(event.getValue().getHourlySavings()).isZero();
    }

    @Test
    void disable_whenRolloutConflicts_propagatesTypedErrorAndRecordsNothing() throws Exception {
        givenMig("web-mig", "web-tpl-spot-1");
        when(restTemplateService.getInstanceTemplate(PROJECT, null, "web-tpl-spot-1"))
                .thenReturn(templateJson("SPOT", "Spot template created by XamOps AutoSpotting", null));
        AutoSpottingBlockedException conflict = AutoSpottingBlockedException.gcpUpdateConflict("web-mig");
        doThrow(conflict).when(restTemplateService)
                .startRollingUpdate(eq(PROJECT), eq(REGION), eq("web-mig"), anyString(), eq(3));

        assertThatThrownBy(() -> service.disableAutoSpotting(PROJECT, REGION, "web-mig")).isSameAs(conflict);

        verify(restTemplateService).createSpotTemplate(eq(PROJECT), isNull(), anyString(), any(), eq(false));
        verify(eventRepository, never()).save(any());
    }

    @Test
    void disable_whenRolloutFailsGenerically_wrapsAndRecordsNothing() throws Exception {
        givenMig("web-mig", "web-tpl-spot-1");
        when(restTemplateService.getInstanceTemplate(PROJECT, null, "web-tpl-spot-1"))
                .thenReturn(templateJson("SPOT", "customer template", null));
        doThrow(new RuntimeException("Operation failed: boom")).when(restTemplateService)
                .startRollingUpdate(any(), any(), any(), any(), anyInt());

        assertThatThrownBy(() -> service.disableAutoSpotting(PROJECT, REGION, "web-mig"))
                .isNotInstanceOf(AutoSpottingBlockedException.class)
                .hasMessageContaining("Failed to disable Spot")
                .hasMessageContaining("boom");

        verify(eventRepository, never()).save(any());
    }

    @Test
    void disable_onStandardTemplate_isNoOp() throws Exception {
        givenMig("web-mig", "web-tpl");
        when(restTemplateService.getInstanceTemplate(PROJECT, null, "web-tpl"))
                .thenReturn(templateJson("STANDARD", "customer template", null));

        service.disableAutoSpotting(PROJECT, REGION, "web-mig");

        verify(restTemplateService, never()).createSpotTemplate(any(), any(), any(), any(), anyBoolean());
        verify(restTemplateService, never()).startRollingUpdate(any(), any(), any(), any(), anyInt());
        verifyNoInteractions(eventRepository);
    }

    // ── listing ──────────────────────────────────────────────────────────────

    @Test
    void listing_flagsGkePools_andZeroesTheirPotentialSavings() {
        InstanceGroupManagersClient igmClient = mock(InstanceGroupManagersClient.class);
        InstanceGroupManagersClient.AggregatedListPagedResponse page =
                mock(InstanceGroupManagersClient.AggregatedListPagedResponse.class);
        when(gcpClientProvider.getInstanceGroupManagersClient(PROJECT)).thenReturn(Optional.of(igmClient));
        when(igmClient.aggregatedList(PROJECT)).thenReturn(page);
        InstanceGroupManagersScopedList scoped = InstanceGroupManagersScopedList.newBuilder()
                .addInstanceGroupManagers(zonalMig(GKE_MIG, "gke-tpl"))
                .addInstanceGroupManagers(zonalMig("labelled-mig", "lbl-tpl"))
                .addInstanceGroupManagers(zonalMig("web-mig", "web-tpl-od-1"))
                .build();
        when(page.iterateAll()).thenReturn(List.of(Map.entry("zones/us-central1-a", scoped)));

        InstanceTemplatesClient templatesClient = mock(InstanceTemplatesClient.class);
        when(gcpClientProvider.getInstanceTemplatesClient(PROJECT)).thenReturn(Optional.of(templatesClient));
        when(templatesClient.get(PROJECT, "gke-tpl")).thenThrow(new RuntimeException("not readable"));
        when(templatesClient.get(PROJECT, "lbl-tpl")).thenReturn(InstanceTemplate.newBuilder()
                .setName("lbl-tpl")
                .setProperties(InstanceProperties.newBuilder()
                        .setMachineType("e2-standard-4").putLabels("goog-gke-node", ""))
                .build());
        when(templatesClient.get(PROJECT, "web-tpl-od-1")).thenReturn(InstanceTemplate.newBuilder()
                .setName("web-tpl-od-1")
                .setDescription("On-demand template created by XamOps AutoSpotting")
                .setProperties(InstanceProperties.newBuilder().setMachineType("e2-standard-4"))
                .build());
        when(pricingService.getMachineTypePricing(any(), any(), any())).thenReturn(
                GcpPricingService.PricingInfo.priced(0.10, 0.03, GcpPricingService.PriceSource.CATALOG, null));

        Map<String, GcpAutoSpottingService.GcpAutoSpottingGroupDto> byName = new java.util.HashMap<>();
        service.listMigsAllRegionsWithCosts(PROJECT, true).forEach(m -> byName.put(m.name, m));

        assertThat(byName.get(GKE_MIG).gkeManagedPool).isTrue();          // name-only fallback
        assertThat(byName.get(GKE_MIG).potentialHourlySavings).isZero();
        assertThat(byName.get("labelled-mig").gkeManagedPool).isTrue();   // label
        assertThat(byName.get("labelled-mig").xamopsManagedTemplate).isFalse();
        assertThat(byName.get("labelled-mig").potentialHourlySavings).isZero();
        assertThat(byName.get("web-mig").gkeManagedPool).isFalse();
        assertThat(byName.get("web-mig").xamopsManagedTemplate).isTrue();
        assertThat(byName.get("web-mig").potentialHourlySavings).isCloseTo(3 * 0.07, within(1e-9));
    }

    private static InstanceGroupManager zonalMig(String name, String template) {
        return InstanceGroupManager.newBuilder()
                .setName(name)
                .setZone("https://www.googleapis.com/compute/v1/projects/proj/zones/us-central1-a")
                .setInstanceTemplate(TEMPLATE_BASE + template)
                .setTargetSize(3)
                .build();
    }

    // ── static helpers ───────────────────────────────────────────────────────

    @Test
    void gkeDetection_byNameOrLabel() {
        assertThat(GcpAutoSpottingService.isGkeManaged(GKE_MIG, null)).isTrue();
        assertThat(GcpAutoSpottingService.isGkeManaged("web-mig", Map.of("goog-gke-node", ""))).isTrue();
        assertThat(GcpAutoSpottingService.isGkeManaged("web-mig", Map.of("team", "web"))).isFalse();
        assertThat(GcpAutoSpottingService.isGkeManaged("web-mig", null)).isFalse();
        assertThat(GcpAutoSpottingService.isGkeManagedJson("web-mig",
                templateJson("SPOT", "x", "{'goog-gke-node':''}"))).isTrue();
        assertThat(GcpAutoSpottingService.isGkeManagedJson("web-mig", templateJson("SPOT", "x", null))).isFalse();
    }

    @Test
    void xamopsMarker_detectedFromDescription() {
        assertThat(GcpAutoSpottingService.isXamOpsCreatedTemplate("Spot template created by XamOps AutoSpotting"))
                .isTrue();
        assertThat(GcpAutoSpottingService.isXamOpsCreatedTemplate("On-demand template created by XamOps AutoSpotting"))
                .isTrue();
        assertThat(GcpAutoSpottingService.isXamOpsCreatedTemplate("customer template")).isFalse();
        assertThat(GcpAutoSpottingService.isXamOpsCreatedTemplate(null)).isFalse();
        assertThat(GcpAutoSpottingService.isXamOpsCreatedTemplateJson(
                templateJson("SPOT", "Spot template created by XamOps AutoSpotting", null))).isTrue();
    }
}
