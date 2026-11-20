package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.matters.datastores.EndpointFileActivity;
import com.xammer.cloud.dto.matters.datastores.EndpointFileLineage;
import com.xammer.cloud.dto.matters.datastores.EndpointFileOverview;
import com.xammer.cloud.dto.matters.datastores.SaasFileCopies;
import com.xammer.cloud.dto.matters.datastores.SaasFileLineage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Endpoint file drawer's service (plan C18 to C20) with the real mapper, a mocked client, a mocked build 2
 * {@link SaasFileService} and an in-memory cache. Values are invented; samples carry {@link #MARKER}.
 */
class EndpointFileServiceTest {

    private static final MattersCredential CREDENTIAL =
            new MattersCredential(10L, "https://matters.test", "synthetic-key", "k1", "p1");
    private static final String FILE_ID = "0000aaaa-0000-4000-8000-00000000000c";
    private static final String MARKER = "<<SYNTHETIC-SAMPLE-7A3F>>";

    private static final String OVERVIEW = """
            {"success": true, "overview": {"name": "notes.txt", "extension": "txt", "owner": "robin",
              "sensitive_entities_count": 3, "device": {"hostname": "Team-Laptop-02"},
              "sample_matches": [{"samples": [{"match_text": "%1$s"}]}]}}
            """.formatted(MARKER);
    private static final String AUDIT_LOGS = """
            {"success": true, "audit_logs": [{"action": "TAG_APPLIED", "timestamp": 1789725004,
              "performed_by": "robin@example.test", "selected_tag": {"name": "Confidential"},
              "keywords_written": true, "mip_label_written": true}]}
            """;
    private static final String ASSET = """
            {"success": true, "data": {"asset": {"title": "notes.txt", "device": "Team-Laptop-02",
              "os_user": "robin", "first_seen": 1789725004}, "events": [], "edges": [], "alerts": []}}
            """;
    private static final SaasFileLineage COPIES_LINEAGE = new SaasFileLineage(true, null, List.of(
            new SaasFileLineage.Lane("copy-0", "notes.txt", null, null, null, "MAC", "ENDPOINT_AGENT", "Endpoint",
                    true, 1789725004L)), List.of());

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> events = new ArrayList<>();

    private MattersCredentialResolver resolver;
    private MattersApiClient apiClient;
    private SaasFileService saasFileService;
    private InMemoryRedisCache cache;
    private EndpointFileService service;

    @BeforeEach
    void setUp() {
        resolver = mock(MattersCredentialResolver.class);
        apiClient = mock(MattersApiClient.class);
        saasFileService = mock(SaasFileService.class);
        cache = new InMemoryRedisCache(events);
        when(resolver.resolve(10L)).thenAnswer(inv -> {
            events.add("resolve");
            return CREDENTIAL;
        });
        when(apiClient.endpointFileOverview(CREDENTIAL, FILE_ID)).thenAnswer(inv -> parse(OVERVIEW));
        when(apiClient.mipFileAuditLogs(CREDENTIAL, FILE_ID)).thenAnswer(inv -> parse(AUDIT_LOGS));
        when(apiClient.endpointLineageAsset(CREDENTIAL, FILE_ID)).thenAnswer(inv -> parse(ASSET));
        when(saasFileService.lineage(10L, FILE_ID)).thenReturn(COPIES_LINEAGE);
        service = new EndpointFileService(resolver, apiClient, new FilesPageMapper(), saasFileService, cache);
    }

    private JsonNode parse(String raw) {
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static MattersApiException failure(MattersApiException.Reason reason) {
        return new MattersApiException(reason, "synthetic");
    }

    @Test
    @DisplayName("overview: credential first, cached under fepfile for 2 minutes, no sample in the entry; "
            + "ASSET_NOT_FOUND propagates")
    void overview() {
        EndpointFileOverview overview = service.overview(10L, FILE_ID);
        service.overview(10L, FILE_ID);
        assertThat(events).startsWith("resolve", "get dspm:fepfile:10:" + FILE_ID);
        assertThat(overview.device()).isEqualTo("Team-Laptop-02");
        verify(apiClient, times(1)).endpointFileOverview(CREDENTIAL, FILE_ID);
        assertThat(cache.puts).extracting(InMemoryRedisCache.Put::key, InMemoryRedisCache.Put::minutes)
                .containsExactly(tuple("dspm:fepfile:10:" + FILE_ID, 2));
        assertThat(cache.puts.get(0).json()).doesNotContain(MARKER);

        String other = "0000aaaa-0000-4000-8000-00000000000d";
        when(apiClient.endpointFileOverview(CREDENTIAL, other))
                .thenThrow(failure(MattersApiException.Reason.ASSET_NOT_FOUND));
        assertThatThrownBy(() -> service.overview(10L, other)).isInstanceOfSatisfying(MattersApiException.class,
                e -> assertThat(e.getReason()).isEqualTo(MattersApiException.Reason.ASSET_NOT_FOUND));
        assertThat(cache.puts).hasSize(1);
    }

    @Test
    @DisplayName("activity: the MIP route mapped and cached under fepact; ASSET_NOT_FOUND is empty; a 502 caches nothing")
    void activity() {
        EndpointFileActivity activity = service.activity(10L, FILE_ID);
        assertThat(activity.entries()).extracting(EndpointFileActivity.Entry::actionLabel)
                .containsExactly("Tag applied");
        assertThat(cache.puts).extracting(InMemoryRedisCache.Put::key, InMemoryRedisCache.Put::minutes)
                .containsExactly(tuple("dspm:fepact:10:" + FILE_ID, 2));
        verify(apiClient, never()).remediationLogs(CREDENTIAL, FILE_ID);

        String unknown = "0000aaaa-0000-4000-8000-00000000000d";
        when(apiClient.mipFileAuditLogs(CREDENTIAL, unknown)).thenThrow(failure(MattersApiException.Reason.ASSET_NOT_FOUND));
        assertThat(service.activity(10L, unknown).entries()).isEmpty();

        String failing = "0000aaaa-0000-4000-8000-00000000000e";
        when(apiClient.mipFileAuditLogs(CREDENTIAL, failing))
                .thenThrow(failure(MattersApiException.Reason.UPSTREAM_UNAVAILABLE));
        assertThatThrownBy(() -> service.activity(10L, failing)).isInstanceOf(MattersApiException.class);
        assertThat(cache.contains("dspm:fepact:10:" + failing)).isFalse();
    }

    @Test
    @DisplayName("lineage: only the asset is cached under feplin for 5 minutes; build 2's copies lineage is read "
            + "through its service on every request")
    void lineage() {
        EndpointFileLineage lineage = service.lineage(10L, FILE_ID);
        EndpointFileLineage again = service.lineage(10L, FILE_ID);
        EndpointFileLineage.Asset asset = new EndpointFileLineage.Asset("notes.txt", "Team-Laptop-02", "robin",
                1789725004L, List.of());
        assertThat(lineage.asset()).isEqualTo(asset);
        assertThat(again.asset()).isEqualTo(asset);
        assertThat(lineage.copies()).isEqualTo(COPIES_LINEAGE);
        assertThat(again.copies()).isEqualTo(COPIES_LINEAGE);
        verify(apiClient, times(1)).endpointLineageAsset(CREDENTIAL, FILE_ID);
        verify(saasFileService, times(2)).lineage(10L, FILE_ID);
        assertThat(cache.puts).extracting(InMemoryRedisCache.Put::key, InMemoryRedisCache.Put::minutes)
                .containsExactly(tuple("dspm:feplin:10:" + FILE_ID, 5));
        assertThat(cache.puts.get(0).json()).doesNotContain("copy-0");
    }

    @Test
    @DisplayName("lineage: an asset 404 gives a null asset with the copies; an asset 502 propagates and caches nothing")
    void lineageAssetFailures() {
        when(apiClient.endpointLineageAsset(CREDENTIAL, FILE_ID))
                .thenThrow(failure(MattersApiException.Reason.ASSET_NOT_FOUND));
        EndpointFileLineage lineage = service.lineage(10L, FILE_ID);
        assertThat(lineage.asset()).isNull();
        assertThat(lineage.copies()).isEqualTo(COPIES_LINEAGE);
        assertThat(service.lineage(10L, FILE_ID).asset()).isNull();
        verify(apiClient, times(1)).endpointLineageAsset(CREDENTIAL, FILE_ID);

        String failing = "0000aaaa-0000-4000-8000-00000000000e";
        when(apiClient.endpointLineageAsset(CREDENTIAL, failing))
                .thenThrow(failure(MattersApiException.Reason.UPSTREAM_UNAVAILABLE));
        assertThatThrownBy(() -> service.lineage(10L, failing)).isInstanceOfSatisfying(MattersApiException.class,
                e -> assertThat(e.getReason()).isEqualTo(MattersApiException.Reason.UPSTREAM_UNAVAILABLE));
        assertThat(cache.contains("dspm:feplin:10:" + failing)).isFalse();
        verify(saasFileService, never()).lineage(10L, failing);
    }

    @Test
    @DisplayName("copies: delegated to build 2's SaasFileService with the UUID")
    void copies() {
        SaasFileCopies copies = new SaasFileCopies(true, List.of(), 1);
        when(saasFileService.copies(10L, FILE_ID)).thenReturn(copies);
        assertThat(service.copies(10L, FILE_ID)).isSameAs(copies);
        verify(saasFileService).copies(10L, FILE_ID);
    }
}
