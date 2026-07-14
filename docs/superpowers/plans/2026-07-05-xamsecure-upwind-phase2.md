# XamSecure Phase 2 (Upwind) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Extend XamSecure with three live-verified Upwind capabilities — an **Inventory** tab, an **API Security** tab, and **real-time threat detections via webhook** plus **acknowledge/resolve actions** on detections.

**Architecture:** Reuse the Phase 1 pattern (UpwindApiClient → sync/service → tenant-filtered controller → React tab). Inventory follows the full synced-table pattern (v2 POST search). API Security is read-through (no table — Upwind's `cloud-account-id` query param does tenant filtering natively, cached in Redis). Webhooks add a public shared-secret ingest endpoint that upserts detections via the existing `UpwindSyncService`, plus an admin endpoint to register the webhook in Upwind. Detection actions PATCH through to Upwind and update the local row.

**Tech Stack:** Spring Boot (javax.persistence, Lombok, JUnit 5 + Mockito, Flyway, RestTemplate, Jackson), React + TypeScript + Vite + Tailwind, `apiClient`.

**Specs:** `docs/superpowers/specs/2026-07-02-xamsecure-upwind-design.md` and `docs/superpowers/specs/2026-07-03-upwind-parity-matrix.md` (Phase 2 verification section — read it; it has the exact request/response shapes).

## Global Constraints

- No Maven wrapper: `mvn -f xamops-service/pom.xml …` from repo root.
- UI theme: black/white/grey only; no emojis; severity chips in greys; match existing XamSecure tabs exactly.
- Frontend authenticated calls via `apiClient` from `frontend-app/src/utils/apiClient`.
- **Every Upwind request MUST send `User-Agent: XamOps/1.0`** (WAF rejects default agents). The existing `UpwindApiClient.fetchAll` already does; new methods must too.
- Upwind creds from env only (`.env.local`); never commit or expose to frontend.
- Backend package root `com.xammer.cloud`.
- **New Vite proxy entry** for any new `/api/...` prefix not already proxied (Phase 1 missed this; `/api/xamsecure` is already proxied so new sub-paths are covered).
- Tenant isolation is non-negotiable: every read resolves the caller's owned cloud-account IDs (`XamSecureService.resolveAccountIds(clientId, accountId)`) and filters to them.
- Commit after every task; `git pull --rebase origin main` before any push.
- Base commit for this plan: `451029f96`.

### Verified Upwind facts (do not re-derive — from parity matrix)
- Inventory: `POST /v2/organizations/{org}/inventory/assets/search?limit=N` body `{"conditions":[...]}`. Tenant filter = add `{"field":"cloud_account_id","operator":"eq","value":["<id>"]}` **paired with another condition** (alone → 500). Cursor paging via `metadata.next_cursor`. Item fields: `id, asset_category, asset_sub_category, cloud_provider, cloud_account_id, cloud_resource_id, name, region, label`.
- API Security: `GET /v1/organizations/{org}/apisecurity-endpoints?cloud-account-id=<id>&per-page=100` (Link paging). Item fields: `id, method, uri, resource_id, first_seen_time, last_seen_time, risk_overview`.
- Webhook create: `POST /v1/organizations/{org}/integration-webhooks` body `{"name":..,"vendor":"CUSTOM","config":{"url":..,"authentication":{"type":"BEARER","token":".."}}}`.
- Detection update: `PATCH /v1/organizations/{org}/threat-detections/{id}` body `{"status":"ARCHIVED"}` (mutable field: `status`).

---

### Task 1: UpwindApiClient — Phase 2 HTTP methods

**Files:**
- Modify: `xamops-service/src/main/java/com/xammer/cloud/service/upwind/UpwindApiClient.java`
- Test: `xamops-service/src/test/java/com/xammer/cloud/service/upwind/UpwindApiClientPhase2Test.java`

**Interfaces:**
- Consumes: existing private `getToken()`, fields `apiBaseUrl`, `orgId`, `restTemplate`, `objectMapper`, and the `USER_AGENT` convention.
- Produces (used by Tasks 3, 6, 8, 10):
  - `List<JsonNode> searchInventory(List<Map<String,Object>> conditions, int maxItems)` — v2 POST cursor paging
  - `List<JsonNode> fetchApiSecurityEndpoints(String cloudAccountId, int maxPages)` — v1 GET with `cloud-account-id` + Link paging
  - `JsonNode createWebhook(JsonNode body)` — v1 POST, returns created webhook JSON
  - `void updateDetectionStatus(String detectionId, String status)` — v1 PATCH

- [ ] **Step 1: Write the failing test**

```java
package com.xammer.cloud.service.upwind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UpwindApiClientPhase2Test {

    private RestTemplate restTemplate;
    private UpwindApiClient client;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        client = new UpwindApiClient(new ObjectMapper(), restTemplate);
        client.setClientId("id"); client.setClientSecret("sec");
        client.setOrgId("org_test");
        client.setTokenUrl("https://oauth.example/token");
        client.setApiBaseUrl("https://api.example");
        client.setAudience("https://api.example");
        when(restTemplate.exchange(eq("https://oauth.example/token"), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"access_token\":\"tok\",\"expires_in\":86400}"));
    }

    @Test
    void searchInventoryFollowsCursorAndSendsUserAgent() {
        String base = "https://api.example/v2/organizations/org_test/inventory/assets/search";
        ResponseEntity<String> page1 = ResponseEntity.ok(
                "{\"items\":[{\"id\":\"a\"}],\"metadata\":{\"next_cursor\":\"c1\"}}");
        ResponseEntity<String> page2 = ResponseEntity.ok(
                "{\"items\":[{\"id\":\"b\"}],\"metadata\":{}}");
        when(restTemplate.exchange(eq(URI.create(base + "?limit=100")), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class))).thenReturn(page1);
        when(restTemplate.exchange(eq(URI.create(base + "?limit=100&cursor=c1")), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class))).thenReturn(page2);

        List<JsonNode> items = client.searchInventory(
                List.of(Map.of("field", "cloud_provider", "operator", "eq", "value", List.of("AWS"))), 1000);
        assertEquals(2, items.size());
        assertEquals("a", items.get(0).get("id").asText());
        assertEquals("b", items.get(1).get("id").asText());
    }

    @Test
    void fetchApiSecurityEndpointsPassesCloudAccountIdAndPages() {
        HttpHeaders h1 = new HttpHeaders();
        h1.add("Link", "<https://api.example/v1/organizations/org_test/apisecurity-endpoints?cloud-account-id=111&page-token=t2>; rel=\"next\"");
        ResponseEntity<String> p1 = new ResponseEntity<>("[{\"id\":\"e1\"}]", h1, HttpStatus.PARTIAL_CONTENT);
        ResponseEntity<String> p2 = ResponseEntity.ok("[{\"id\":\"e2\"}]");
        when(restTemplate.exchange(eq(URI.create(
                "https://api.example/v1/organizations/org_test/apisecurity-endpoints?cloud-account-id=111&per-page=100")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class))).thenReturn(p1);
        when(restTemplate.exchange(eq(URI.create(
                "https://api.example/v1/organizations/org_test/apisecurity-endpoints?cloud-account-id=111&page-token=t2")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class))).thenReturn(p2);

        List<JsonNode> items = client.fetchApiSecurityEndpoints("111", 10);
        assertEquals(2, items.size());
        assertEquals("e2", items.get(1).get("id").asText());
    }

    @Test
    void createWebhookPostsBodyAndReturnsJson() {
        ObjectMapper m = new ObjectMapper();
        JsonNode body = m.createObjectNode().put("name", "xamops");
        when(restTemplate.exchange(eq(URI.create(
                "https://api.example/v1/organizations/org_test/integration-webhooks")),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"id\":\"wh1\",\"name\":\"xamops\"}"));
        JsonNode created = client.createWebhook(body);
        assertEquals("wh1", created.get("id").asText());
    }

    @Test
    void updateDetectionStatusPatchesStatus() {
        when(restTemplate.exchange(eq(URI.create(
                "https://api.example/v1/organizations/org_test/threat-detections/uwnd-1")),
                eq(HttpMethod.PATCH), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        client.updateDetectionStatus("uwnd-1", "ARCHIVED");
        verify(restTemplate).exchange(eq(URI.create(
                "https://api.example/v1/organizations/org_test/threat-detections/uwnd-1")),
                eq(HttpMethod.PATCH), any(HttpEntity.class), eq(String.class));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f xamops-service/pom.xml test -q -Dtest=UpwindApiClientPhase2Test`
Expected: COMPILATION ERROR (methods don't exist)

- [ ] **Step 3: Add the methods to UpwindApiClient**

Add these methods (place after `fetchConfigurationFindings`). They reuse `getToken()` and the UA convention; add a private `authHeaders()` helper to DRY the bearer+UA+content-type headers.

```java
    /** v2 inventory search with cursor paging. conditions is the request body's "conditions" array. */
    public List<JsonNode> searchInventory(java.util.List<java.util.Map<String, Object>> conditions, int maxItems) {
        java.util.List<JsonNode> items = new java.util.ArrayList<>();
        String base = apiBaseUrl + "/v2/organizations/" + orgId + "/inventory/assets/search?limit=100";
        String url = base;
        try {
            String body = objectMapper.writeValueAsString(java.util.Map.of("conditions", conditions));
            while (url != null && items.size() < maxItems) {
                HttpHeaders headers = authHeaders();
                ResponseEntity<String> resp = restTemplate.exchange(
                        java.net.URI.create(url), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
                JsonNode json = objectMapper.readTree(resp.getBody());
                for (JsonNode item : json.path("items")) items.add(item);
                String cursor = json.path("metadata").path("next_cursor").asText(null);
                url = (cursor != null && !cursor.isEmpty()) ? base + "&cursor=" + cursor : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Upwind inventory search failed", e);
        }
        return items;
    }

    /** v1 API-security endpoints, tenant-filtered by cloud-account-id query param, Link paging. */
    public List<JsonNode> fetchApiSecurityEndpoints(String cloudAccountId, int maxPages) {
        java.util.List<JsonNode> items = new java.util.ArrayList<>();
        String url = apiBaseUrl + "/v1/organizations/" + orgId
                + "/apisecurity-endpoints?cloud-account-id=" + cloudAccountId + "&per-page=100";
        int pages = 0;
        while (url != null && pages < maxPages) {
            HttpHeaders headers = authHeaders();
            ResponseEntity<String> resp = restTemplate.exchange(
                    java.net.URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            try {
                JsonNode body = objectMapper.readTree(resp.getBody());
                if (body.isArray()) body.forEach(items::add);
            } catch (Exception e) {
                throw new IllegalStateException("Upwind apisecurity response invalid", e);
            }
            url = nextLink(resp.getHeaders());
            pages++;
        }
        return items;
    }

    /** v1 create webhook integration. Returns the created object. */
    public JsonNode createWebhook(JsonNode body) {
        String url = apiBaseUrl + "/v1/organizations/" + orgId + "/integration-webhooks";
        try {
            HttpHeaders headers = authHeaders();
            ResponseEntity<String> resp = restTemplate.exchange(java.net.URI.create(url),
                    HttpMethod.POST, new HttpEntity<>(objectMapper.writeValueAsString(body), headers), String.class);
            return objectMapper.readTree(resp.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Upwind webhook create failed", e);
        }
    }

    /** v1 PATCH a detection's status (e.g. "ARCHIVED"). */
    public void updateDetectionStatus(String detectionId, String status) {
        String url = apiBaseUrl + "/v1/organizations/" + orgId + "/threat-detections/" + detectionId;
        try {
            HttpHeaders headers = authHeaders();
            String body = objectMapper.writeValueAsString(java.util.Map.of("status", status));
            restTemplate.exchange(java.net.URI.create(url), HttpMethod.PATCH,
                    new HttpEntity<>(body, headers), String.class);
        } catch (Exception e) {
            throw new IllegalStateException("Upwind detection update failed", e);
        }
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(getToken());
        headers.set(HttpHeaders.USER_AGENT, "XamOps/1.0");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
```

Note: `PATCH` via the default `SimpleClientHttpRequestFactory` is not supported. If the RestTemplate bean uses it, the PATCH will throw at runtime. Check the existing `RestTemplateConfig` bean; if it uses `SimpleClientHttpRequestFactory`, switch it to `HttpComponentsClientHttpRequestFactory` (Apache HttpClient is already a transitive dep via AWS SDK; if not present, add `org.apache.httpcomponents.client5:httpclient5`). The unit test mocks RestTemplate so it passes regardless; this note is for runtime. Verify in Task 11's smoke test.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f xamops-service/pom.xml test -q -Dtest=UpwindApiClientPhase2Test`
Expected: 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/service/upwind/UpwindApiClient.java xamops-service/src/test/java/com/xammer/cloud/service/upwind/UpwindApiClientPhase2Test.java
git commit -m "feat(xamsecure): add Phase 2 Upwind client methods (inventory search, apisec, webhook, detection PATCH)"
```

---

### Task 2: Inventory migration + entity + repository

**Files:**
- Create: `xamops-service/src/main/resources/db/migration/V20260705_1__xamsecure_inventory.sql`
- Create: `xamops-service/src/main/java/com/xammer/cloud/domain/UpwindInventoryAsset.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/repository/UpwindInventoryAssetRepository.java`

**Interfaces:**
- Produces (Tasks 3, 4): entity via Lombok `@Data`; repo methods `findByUpwindId`, `findByCloudAccountIdInOrderByNameAsc(List<String>, Pageable)`, `findByCloudAccountIdInAndAssetCategoryOrderByNameAsc(...)`, `countByCloudAccountIdIn(List<String>)`, `markResolvedNotSyncedSince(Instant)` (uses a `status` column so absent assets can be pruned).

- [ ] **Step 1: Write the migration**

```sql
CREATE TABLE upwind_inventory_asset (
    id BIGSERIAL PRIMARY KEY,
    upwind_id VARCHAR(128) NOT NULL UNIQUE,
    asset_category VARCHAR(64),
    asset_sub_category VARCHAR(64),
    label VARCHAR(128),
    cloud_provider VARCHAR(16),
    cloud_account_id VARCHAR(64),
    cloud_resource_id VARCHAR(512),
    name VARCHAR(256),
    region VARCHAR(64),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    raw_payload TEXT,
    synced_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_uia_account ON upwind_inventory_asset (cloud_account_id);
CREATE INDEX idx_uia_category ON upwind_inventory_asset (asset_category);
```

- [ ] **Step 2: Write the entity**

```java
package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Data
@NoArgsConstructor
@Table(name = "upwind_inventory_asset")
public class UpwindInventoryAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "upwind_id", nullable = false, unique = true)
    private String upwindId;

    @Column(name = "asset_category")
    private String assetCategory;

    @Column(name = "asset_sub_category")
    private String assetSubCategory;

    private String label;

    @Column(name = "cloud_provider")
    private String cloudProvider;

    @Column(name = "cloud_account_id")
    private String cloudAccountId;

    @Column(name = "cloud_resource_id", length = 512)
    private String cloudResourceId;

    private String name;
    private String region;

    /** ACTIVE / RESOLVED (absent from latest sync) */
    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "raw_payload", columnDefinition = "TEXT")
    private String rawPayload;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;
}
```

- [ ] **Step 3: Write the repository**

```java
package com.xammer.cloud.repository;

import com.xammer.cloud.domain.UpwindInventoryAsset;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UpwindInventoryAssetRepository extends JpaRepository<UpwindInventoryAsset, Long> {

    Optional<UpwindInventoryAsset> findByUpwindId(String upwindId);

    Page<UpwindInventoryAsset> findByCloudAccountIdInOrderByNameAsc(List<String> accountIds, Pageable pageable);

    Page<UpwindInventoryAsset> findByCloudAccountIdInAndAssetCategoryOrderByNameAsc(
            List<String> accountIds, String assetCategory, Pageable pageable);

    @Query("SELECT a.assetCategory, COUNT(a) FROM UpwindInventoryAsset a " +
           "WHERE a.cloudAccountId IN :accountIds AND a.status = 'ACTIVE' GROUP BY a.assetCategory")
    List<Object[]> countByCategory(@Param("accountIds") List<String> accountIds);

    @Modifying
    @Query("UPDATE UpwindInventoryAsset a SET a.status = 'RESOLVED' " +
           "WHERE a.syncedAt < :runStart AND a.status <> 'RESOLVED'")
    int markResolvedNotSyncedSince(@Param("runStart") Instant runStart);
}
```

- [ ] **Step 4: Compile**

Run: `mvn -f xamops-service/pom.xml compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add xamops-service/src/main/resources/db/migration/V20260705_1__xamsecure_inventory.sql xamops-service/src/main/java/com/xammer/cloud/domain/UpwindInventoryAsset.java xamops-service/src/main/java/com/xammer/cloud/repository/UpwindInventoryAssetRepository.java
git commit -m "feat(xamsecure): add inventory asset table, entity, repository"
```

---

### Task 3: Inventory sync

**Files:**
- Modify: `xamops-service/src/main/java/com/xammer/cloud/service/upwind/UpwindSyncService.java`
- Modify: `xamops-service/src/main/java/com/xammer/cloud/service/upwind/UpwindSyncScheduler.java`
- Modify: `xamops-service/src/test/java/com/xammer/cloud/service/upwind/UpwindSyncServiceTest.java`

**Interfaces:**
- Consumes: Task 1 `apiClient.searchInventory(conditions, maxItems)`; Task 2 repo; existing `recordSuccess/recordError` helpers and `text()/instant()` in UpwindSyncService.
- Produces: `syncInventory()` on UpwindSyncService (called by `syncAll()` and scheduler); sync-state domain `INVENTORY`.

- [ ] **Step 1: Add the failing test to UpwindSyncServiceTest**

Add a mock for the new repo (constructor changes — see Step 3) and this test:

```java
    @Test
    void syncInventoryUpsertsAssets() {
        when(apiClient.isConfigured()).thenReturn(true);
        String assetJson = "{\"id\":\"uwr-1\",\"asset_category\":\"compute_platform\"," +
            "\"asset_sub_category\":\"virtual_machine\",\"label\":\"aws_ec2_instance\"," +
            "\"cloud_provider\":\"AWS\",\"cloud_account_id\":\"605134457560\"," +
            "\"cloud_resource_id\":\"i-0613\",\"name\":\"XamCloud-Website\",\"region\":\"ap-south-2\"}";
        when(apiClient.searchInventory(anyList(), anyInt())).thenReturn(List.of(parse(assetJson)));
        when(inventoryRepo.findByUpwindId("uwr-1")).thenReturn(Optional.empty());

        service.syncInventory();

        org.mockito.ArgumentCaptor<com.xammer.cloud.domain.UpwindInventoryAsset> cap =
            org.mockito.ArgumentCaptor.forClass(com.xammer.cloud.domain.UpwindInventoryAsset.class);
        verify(inventoryRepo).save(cap.capture());
        assertEquals("uwr-1", cap.getValue().getUpwindId());
        assertEquals("605134457560", cap.getValue().getCloudAccountId());
        assertEquals("aws_ec2_instance", cap.getValue().getLabel());
        verify(inventoryRepo).markResolvedNotSyncedSince(any());
    }
```

Declare the mock field `private UpwindInventoryAssetRepository inventoryRepo;` and in `setUp()` add `inventoryRepo = mock(UpwindInventoryAssetRepository.class);` and pass it to the `new UpwindSyncService(...)` constructor call (append as the last arg — matching Step 3).

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f xamops-service/pom.xml test -q -Dtest=UpwindSyncServiceTest`
Expected: COMPILATION ERROR (constructor arity / syncInventory missing)

- [ ] **Step 3: Implement syncInventory in UpwindSyncService**

Add `UpwindInventoryAssetRepository inventoryRepo` as a constructor field (append to the existing constructor param list and assign it). Add:

```java
    static final int INVENTORY_MAX_ITEMS = 20000;

    @Transactional
    public void syncInventory() {
        if (!apiClient.isConfigured()) return;
        Instant runStart = Instant.now();
        try {
            // cloud_account_id alone 500s, so pair with a cloud_provider gate per provider.
            for (String provider : new String[]{"AWS", "GCP", "Azure"}) {
                java.util.List<java.util.Map<String, Object>> conditions = java.util.List.of(
                        java.util.Map.of("field", "cloud_provider", "operator", "eq",
                                "value", java.util.List.of(provider)));
                for (JsonNode item : apiClient.searchInventory(conditions, INVENTORY_MAX_ITEMS)) {
                    upsertAsset(item, Instant.now());
                }
            }
            inventoryRepo.markResolvedNotSyncedSince(runStart);
            recordSuccess("INVENTORY");
        } catch (Exception e) {
            log.error("[Upwind] Inventory sync failed: {}", e.getMessage());
            recordError("INVENTORY", e);
        }
    }

    private void upsertAsset(JsonNode item, Instant now) {
        String upwindId = text(item, "id");
        if (upwindId == null) return;
        UpwindInventoryAsset a = inventoryRepo.findByUpwindId(upwindId)
                .orElseGet(UpwindInventoryAsset::new);
        a.setUpwindId(upwindId);
        a.setAssetCategory(text(item, "asset_category"));
        a.setAssetSubCategory(text(item, "asset_sub_category"));
        a.setLabel(text(item, "label"));
        a.setCloudProvider(text(item, "cloud_provider"));
        a.setCloudAccountId(text(item, "cloud_account_id"));
        a.setCloudResourceId(text(item, "cloud_resource_id"));
        a.setName(text(item, "name"));
        a.setRegion(text(item, "region"));
        a.setStatus("ACTIVE");
        a.setRawPayload(item.toString());
        a.setSyncedAt(now);
        inventoryRepo.save(a);
    }
```

Also call `syncInventory();` at the end of `syncAll()` (after posture).

- [ ] **Step 4: Add scheduler hook**

In `UpwindSyncScheduler.fullSync()`, the existing method calls `syncService.syncAll()` which now includes inventory — no change needed. Confirm `syncAll()` calls `syncInventory()`.

- [ ] **Step 5: Run tests**

Run: `mvn -f xamops-service/pom.xml test -q -Dtest=UpwindSyncServiceTest`
Expected: all PASS (existing + new)

- [ ] **Step 6: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/service/upwind/UpwindSyncService.java xamops-service/src/main/java/com/xammer/cloud/service/upwind/UpwindSyncScheduler.java xamops-service/src/test/java/com/xammer/cloud/service/upwind/UpwindSyncServiceTest.java
git commit -m "feat(xamsecure): sync Upwind inventory assets (per-provider search, prune-resolved)"
```

---

### Task 4: Inventory read API

**Files:**
- Modify: `xamops-service/src/main/java/com/xammer/cloud/service/upwind/XamSecureService.java`
- Modify: `xamops-service/src/main/java/com/xammer/cloud/controller/XamSecureController.java`
- Modify: `xamops-service/src/test/java/com/xammer/cloud/service/upwind/XamSecureServiceTest.java`

**Interfaces:**
- Consumes: Task 2 repo; existing `resolveAccountIds(clientId, accountId)`.
- Produces: `GET /api/xamsecure/inventory?accountId=&category=&page=&size=` → Spring `Page<UpwindInventoryAsset>`, tenant-filtered.

- [ ] **Step 1: Add failing test to XamSecureServiceTest**

Add `inventoryRepo` mock to the service constructor (append last arg) and:

```java
    @Test
    void getInventoryFiltersToOwnedAccounts() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        org.springframework.data.domain.Page<com.xammer.cloud.domain.UpwindInventoryAsset> empty =
            org.springframework.data.domain.Page.empty();
        when(inventoryRepo.findByCloudAccountIdInOrderByNameAsc(eq(List.of("111")), any()))
            .thenReturn(empty);
        var page = service.getInventory(1L, null, null, 0, 50);
        assertNotNull(page);
        verify(inventoryRepo).findByCloudAccountIdInOrderByNameAsc(eq(List.of("111")), any());
    }

    @Test
    void getInventoryEmptyWhenNoOwnedAccounts() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of());
        assertTrue(service.getInventory(1L, null, null, 0, 50).isEmpty());
    }
```

- [ ] **Step 2: Run to verify fail**

Run: `mvn -f xamops-service/pom.xml test -q -Dtest=XamSecureServiceTest`
Expected: COMPILATION ERROR

- [ ] **Step 3: Implement getInventory in XamSecureService**

Add `UpwindInventoryAssetRepository inventoryRepo` to the constructor (append last). Add:

```java
    public Page<UpwindInventoryAsset> getInventory(Long clientId, String accountId,
                                                   String category, int page, int size) {
        List<String> ids = resolveAccountIds(clientId, accountId);
        if (ids.isEmpty()) return Page.empty();
        Pageable pageable = PageRequest.of(page, Math.min(size, 200));
        if (category != null && !category.isEmpty()) {
            return inventoryRepo.findByCloudAccountIdInAndAssetCategoryOrderByNameAsc(ids, category, pageable);
        }
        return inventoryRepo.findByCloudAccountIdInOrderByNameAsc(ids, pageable);
    }
```

Add the import for `UpwindInventoryAsset` and `UpwindInventoryAssetRepository`.

- [ ] **Step 4: Add controller endpoint in XamSecureController**

```java
    @GetMapping("/inventory")
    public ResponseEntity<?> inventory(@AuthenticationPrincipal ClientUserDetails user,
                                       @RequestParam(required = false) String accountId,
                                       @RequestParam(required = false) String category,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(xamSecureService.getInventory(user.getClientId(), accountId, category, page, size));
    }
```

- [ ] **Step 5: Run tests**

Run: `mvn -f xamops-service/pom.xml test -q -Dtest=XamSecureServiceTest`
Expected: all PASS

- [ ] **Step 6: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/service/upwind/XamSecureService.java xamops-service/src/main/java/com/xammer/cloud/controller/XamSecureController.java xamops-service/src/test/java/com/xammer/cloud/service/upwind/XamSecureServiceTest.java
git commit -m "feat(xamsecure): tenant-filtered inventory read endpoint"
```

---

### Task 5: Inventory frontend tab

**Files:**
- Modify: `frontend-app/src/types/xamsecure.ts`
- Modify: `frontend-app/src/hooks/useXamSecureData.ts`
- Create: `frontend-app/src/components/XamSecure/InventoryTab.tsx`
- Modify: `frontend-app/src/components/XamSecure/index.ts`
- Modify: `frontend-app/src/pages/XamSecurePage.tsx`

**Interfaces:**
- Consumes: Task 4 endpoint, existing `PageResp<T>`, `apiClient`, `FindingDrawer`, tab registry in `XamSecurePage`.
- Produces: `InventoryAsset` type; hook state `inventory` + `loadInventory(page, category, force)`; `InventoryTab` component; new `'inventory'` tab.

- [ ] **Step 1: Add the type to `types/xamsecure.ts`**

```typescript
export interface InventoryAsset {
  id: number;
  upwindId: string;
  assetCategory: string | null;
  assetSubCategory: string | null;
  label: string | null;
  cloudProvider: string | null;
  cloudAccountId: string | null;
  cloudResourceId: string | null;
  name: string | null;
  region: string | null;
  status: string | null;
  rawPayload: string | null;
}
```

- [ ] **Step 2: Extend the hook**

In `useXamSecureData.ts` add state `const [inventory, setInventory] = useState<PageResp<InventoryAsset> | null>(null);`, import `InventoryAsset`, and add (mirroring `loadVulnerabilities`):

```typescript
  const loadInventory = useCallback(async (page = 0, category = '', force = false) => {
    const key = xamSecureCacheKey(`inventory_${page}_${category}`, selectedAccountId);
    if (!force) {
      const cached = loadCache<PageResp<InventoryAsset>>(key);
      if (cached) { setInventory(cached); return; }
    }
    const data = await fetchJson(
      `/api/xamsecure/inventory?${acct}page=${page}&size=50${category ? `&category=${category}` : ''}`);
    setInventory(data);
    saveCache(key, data);
  }, [acct, selectedAccountId, fetchJson]);
```

Add `inventory` and `loadInventory` to the `useEffect` tab-switch (`else if (activeTab === 'inventory') await loadInventory();`), to the `refresh` switch, and to the returned object.

- [ ] **Step 3: Write InventoryTab (mirror PostureTab structure)**

```tsx
import React, { useState } from 'react';
import type { PageResp, InventoryAsset } from '../../types/xamsecure';
import FindingDrawer from './FindingDrawer';

interface Props {
  data: PageResp<InventoryAsset> | null;
  isLoading: boolean;
  onPage: (page: number) => void;
}

const InventoryTab: React.FC<Props> = ({ data, isLoading, onPage }) => {
  const [selected, setSelected] = useState<InventoryAsset | null>(null);
  const rows = data?.content ?? [];

  return (
    <div className="bg-white border border-gray-200 rounded-xl shadow-sm overflow-hidden">
      <div className="overflow-x-auto">
        <table className="w-full text-xs">
          <thead>
            <tr className="text-left text-[10px] uppercase text-gray-400 border-b border-gray-100">
              <th className="px-5 py-2.5">Name</th>
              <th className="px-5 py-2.5">Type</th>
              <th className="px-5 py-2.5">Category</th>
              <th className="px-5 py-2.5">Provider</th>
              <th className="px-5 py-2.5">Region</th>
              <th className="px-5 py-2.5">Resource ID</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((a) => (
              <tr key={a.upwindId}
                  className="border-b border-gray-50 hover:bg-gray-50 cursor-pointer"
                  onClick={() => setSelected(a)}>
                <td className="px-5 py-3 font-medium text-gray-800 max-w-xs truncate">{a.name}</td>
                <td className="px-5 py-3 text-gray-600">{a.label}</td>
                <td className="px-5 py-3 text-gray-600">
                  {a.assetCategory}
                  <span className="block text-[10px] text-gray-400">{a.assetSubCategory}</span>
                </td>
                <td className="px-5 py-3 text-gray-600">{a.cloudProvider}</td>
                <td className="px-5 py-3 text-gray-600">{a.region}</td>
                <td className="px-5 py-3 font-mono text-[10px] text-gray-500 max-w-xs truncate">{a.cloudResourceId}</td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr>
                <td colSpan={6} className="px-5 py-12 text-center text-gray-400">
                  {isLoading ? 'Loading inventory...' : 'No assets for the selected account.'}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      {data && data.totalPages > 1 && (
        <div className="flex items-center justify-between px-5 py-3 border-t border-gray-200 text-xs text-gray-500">
          <span>Page {data.number + 1} of {data.totalPages} · {data.totalElements} assets</span>
          <div className="flex gap-2">
            <button disabled={data.number === 0} onClick={() => onPage(data.number - 1)}
                    className="px-3 py-1 border border-gray-200 rounded-lg disabled:opacity-40 hover:bg-gray-50">Prev</button>
            <button disabled={data.number + 1 >= data.totalPages} onClick={() => onPage(data.number + 1)}
                    className="px-3 py-1 border border-gray-200 rounded-lg disabled:opacity-40 hover:bg-gray-50">Next</button>
          </div>
        </div>
      )}
      {selected && (
        <FindingDrawer title={selected.name ?? 'Asset'} rawPayload={selected.rawPayload}
                       onClose={() => setSelected(null)} />
      )}
    </div>
  );
};

export default InventoryTab;
```

- [ ] **Step 4: Export + wire into the page**

In `index.ts` add `export { default as InventoryTab } from './InventoryTab';`.
In `XamSecurePage.tsx`: add `'inventory'` to the `ActiveTab` union, add `{ id: 'inventory', label: 'Inventory' }` to the `AriaTabGroup` tabs (place after Posture), destructure `inventory, loadInventory` from the hook, and render:
```tsx
{activeTab === 'inventory' && (
  <InventoryTab data={inventory} isLoading={isLoading} onPage={(p) => loadInventory(p)} />
)}
```
Import `InventoryTab` from `../components/XamSecure`.

- [ ] **Step 5: Type-check + build**

Run: `cd frontend-app && npx tsc --noEmit`
Expected: no errors in XamSecure files.

- [ ] **Step 6: Commit**

```bash
git add frontend-app/src/types/xamsecure.ts frontend-app/src/hooks/useXamSecureData.ts frontend-app/src/components/XamSecure/InventoryTab.tsx frontend-app/src/components/XamSecure/index.ts frontend-app/src/pages/XamSecurePage.tsx
git commit -m "feat(xamsecure): add Inventory tab"
```

---

### Task 6: API Security read-through service + endpoint

**Files:**
- Modify: `xamops-service/src/main/java/com/xammer/cloud/service/upwind/XamSecureService.java`
- Modify: `xamops-service/src/main/java/com/xammer/cloud/controller/XamSecureController.java`
- Modify: `xamops-service/src/test/java/com/xammer/cloud/service/upwind/XamSecureServiceTest.java`

**Interfaces:**
- Consumes: Task 1 `apiClient.fetchApiSecurityEndpoints(cloudAccountId, maxPages)`, `resolveAccountIds`, `RedisCacheService`.
- Produces: `GET /api/xamsecure/api-security?accountId=` → `List<Map<String,Object>>` (flattened endpoint rows), tenant-filtered, Redis-cached 10 min. No DB table (read-through).

- [ ] **Step 1: Add failing test**

Add `UpwindApiClient apiClient` mock to the XamSecureService test constructor (append last arg) and:

```java
    @Test
    void getApiSecurityFetchesPerOwnedAccountAndFlattens() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        com.fasterxml.jackson.databind.JsonNode ep = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree("{\"id\":\"rt-1\",\"method\":\"GET\",\"uri\":\"/version\",\"resource_id\":\"ep-1\"}");
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.fetchApiSecurityEndpoints("111", 20)).thenReturn(List.of(ep));

        var rows = service.getApiSecurity(1L, null);
        assertEquals(1, rows.size());
        assertEquals("GET", rows.get(0).get("method"));
        assertEquals("/version", rows.get(0).get("uri"));
        assertEquals("111", rows.get(0).get("cloudAccountId"));
    }
```

(The test method must declare `throws Exception` for `readTree`.)

- [ ] **Step 2: Run to verify fail**

Run: `mvn -f xamops-service/pom.xml test -q -Dtest=XamSecureServiceTest`
Expected: COMPILATION ERROR

- [ ] **Step 3: Implement getApiSecurity**

Add `UpwindApiClient apiClient` to the constructor (append last). Add:

```java
    private static final int APISEC_CACHE_MINUTES = 10;

    public List<Map<String, Object>> getApiSecurity(Long clientId, String accountId) {
        List<String> ids = resolveAccountIds(clientId, accountId);
        if (ids.isEmpty() || !apiClient.isConfigured()) return List.of();
        String cacheKey = "xamsecure:apisec:" + clientId + ":" + (accountId == null ? "all" : accountId);
        Optional<List<Map<String, Object>>> cached =
                redisCacheService.get(cacheKey, new TypeReference<List<Map<String, Object>>>() {});
        if (cached.isPresent()) return cached.get();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (String id : ids) {
            try {
                for (JsonNode ep : apiClient.fetchApiSecurityEndpoints(id, 20)) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", ep.path("id").asText(null));
                    m.put("method", ep.path("method").asText(null));
                    m.put("uri", ep.path("uri").asText(null));
                    m.put("resourceId", ep.path("resource_id").asText(null));
                    m.put("firstSeenTime", ep.path("first_seen_time").asText(null));
                    m.put("lastSeenTime", ep.path("last_seen_time").asText(null));
                    m.put("internetExposed", ep.path("risk_overview").path("internet_exposure")
                            .path("ingress").has("last_seen_time"));
                    m.put("cloudAccountId", id);
                    rows.add(m);
                }
            } catch (Exception e) {
                log.warn("[Upwind] apisec fetch failed for account {}: {}", id, e.getMessage());
            }
        }
        redisCacheService.put(cacheKey, rows, APISEC_CACHE_MINUTES);
        return rows;
    }
```

Add `import com.fasterxml.jackson.databind.JsonNode;` if not present.

- [ ] **Step 4: Add controller endpoint**

```java
    @GetMapping("/api-security")
    public ResponseEntity<?> apiSecurity(@AuthenticationPrincipal ClientUserDetails user,
                                         @RequestParam(required = false) String accountId) {
        return ResponseEntity.ok(xamSecureService.getApiSecurity(user.getClientId(), accountId));
    }
```

- [ ] **Step 5: Run tests + full backend suite**

Run: `mvn -f xamops-service/pom.xml test -q -Dtest=XamSecureServiceTest`
Expected: all PASS

- [ ] **Step 6: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/service/upwind/XamSecureService.java xamops-service/src/main/java/com/xammer/cloud/controller/XamSecureController.java xamops-service/src/test/java/com/xammer/cloud/service/upwind/XamSecureServiceTest.java
git commit -m "feat(xamsecure): API Security read-through endpoint (tenant-filtered, Redis-cached)"
```

---

### Task 7: API Security frontend tab

**Files:**
- Modify: `frontend-app/src/types/xamsecure.ts`
- Modify: `frontend-app/src/hooks/useXamSecureData.ts`
- Create: `frontend-app/src/components/XamSecure/ApiSecurityTab.tsx`
- Modify: `frontend-app/src/components/XamSecure/index.ts`
- Modify: `frontend-app/src/pages/XamSecurePage.tsx`

**Interfaces:**
- Consumes: Task 6 endpoint (`List`, not `Page`).
- Produces: `ApiEndpoint` type; hook `apiSecurity` + `loadApiSecurity(force)`; `ApiSecurityTab`; `'api-security'` tab.

- [ ] **Step 1: Type**

```typescript
export interface ApiEndpoint {
  id: string;
  method: string | null;
  uri: string | null;
  resourceId: string | null;
  firstSeenTime: string | null;
  lastSeenTime: string | null;
  internetExposed: boolean;
  cloudAccountId: string | null;
}
```

- [ ] **Step 2: Hook**

Add `const [apiSecurity, setApiSecurity] = useState<ApiEndpoint[] | null>(null);`, import `ApiEndpoint`, and:

```typescript
  const loadApiSecurity = useCallback(async (force = false) => {
    const key = xamSecureCacheKey('apisec', selectedAccountId);
    if (!force) {
      const cached = loadCache<ApiEndpoint[]>(key);
      if (cached) { setApiSecurity(cached); return; }
    }
    const data = await fetchJson(`/api/xamsecure/api-security?${acct}`);
    setApiSecurity(data);
    saveCache(key, data);
  }, [acct, selectedAccountId, fetchJson]);
```

Wire into the tab-switch effect (`else if (activeTab === 'api-security') await loadApiSecurity();`), `refresh`, and the return object.

- [ ] **Step 3: ApiSecurityTab**

```tsx
import React from 'react';
import type { ApiEndpoint } from '../../types/xamsecure';

interface Props {
  data: ApiEndpoint[] | null;
  isLoading: boolean;
}

const ApiSecurityTab: React.FC<Props> = ({ data, isLoading }) => {
  const rows = data ?? [];
  if (isLoading && rows.length === 0) {
    return <div className="text-center py-16 text-sm text-gray-400">Loading API endpoints...</div>;
  }
  return (
    <div className="bg-white border border-gray-200 rounded-xl shadow-sm overflow-hidden">
      <div className="overflow-x-auto">
        <table className="w-full text-xs">
          <thead>
            <tr className="text-left text-[10px] uppercase text-gray-400 border-b border-gray-100">
              <th className="px-5 py-2.5">Method</th>
              <th className="px-5 py-2.5">Endpoint</th>
              <th className="px-5 py-2.5">Internet Exposed</th>
              <th className="px-5 py-2.5">First Seen</th>
              <th className="px-5 py-2.5">Last Seen</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((e) => (
              <tr key={e.id} className="border-b border-gray-50 hover:bg-gray-50">
                <td className="px-5 py-3 font-mono font-bold text-gray-700">{e.method}</td>
                <td className="px-5 py-3 font-mono text-gray-800 max-w-md truncate">{e.uri}</td>
                <td className="px-5 py-3">
                  <span className={`px-2 py-0.5 rounded-full text-[10px] font-bold ${
                    e.internetExposed ? 'bg-gray-900 text-white' : 'bg-gray-100 text-gray-500'}`}>
                    {e.internetExposed ? 'Exposed' : 'Internal'}
                  </span>
                </td>
                <td className="px-5 py-3 whitespace-nowrap text-gray-500">
                  {e.firstSeenTime ? new Date(e.firstSeenTime).toLocaleDateString() : '—'}
                </td>
                <td className="px-5 py-3 whitespace-nowrap text-gray-500">
                  {e.lastSeenTime ? new Date(e.lastSeenTime).toLocaleDateString() : '—'}
                </td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={5} className="px-5 py-12 text-center text-gray-400">
                No API endpoints discovered for the selected account.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
};

export default ApiSecurityTab;
```

- [ ] **Step 4: Export + wire into page**

`index.ts`: `export { default as ApiSecurityTab } from './ApiSecurityTab';`
`XamSecurePage.tsx`: add `'api-security'` to `ActiveTab`, `{ id: 'api-security', label: 'API Security' }` tab (after Inventory), destructure `apiSecurity, loadApiSecurity`, render:
```tsx
{activeTab === 'api-security' && <ApiSecurityTab data={apiSecurity} isLoading={isLoading} />}
```

- [ ] **Step 5: Type-check**

Run: `cd frontend-app && npx tsc --noEmit`
Expected: no errors in XamSecure files.

- [ ] **Step 6: Commit**

```bash
git add frontend-app/src/types/xamsecure.ts frontend-app/src/hooks/useXamSecureData.ts frontend-app/src/components/XamSecure/ApiSecurityTab.tsx frontend-app/src/components/XamSecure/index.ts frontend-app/src/pages/XamSecurePage.tsx
git commit -m "feat(xamsecure): add API Security tab"
```

---

### Task 8: Webhook ingest endpoint (real-time detections)

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/controller/XamSecureWebhookController.java`
- Modify: `xamops-service/src/main/java/com/xammer/cloud/service/upwind/UpwindSyncService.java` (expose `ingestDetection(JsonNode)`)
- Modify: `xamops-service/src/main/resources/application.properties` (add `upwind.webhook.secret`)
- Modify: `xamops-service/src/main/java/com/xammer/cloud/config/SecurityConfig.java` (permit the webhook path)
- Test: `xamops-service/src/test/java/com/xammer/cloud/service/upwind/XamSecureWebhookTest.java`

**Interfaces:**
- Consumes: Task 3's `UpwindSyncService`; the existing private `upsertDetection` logic.
- Produces: `POST /api/xamsecure/webhook/upwind` (public, shared-secret via `X-Upwind-Secret` header) → parses Upwind detection payload(s) and upserts. `UpwindSyncService.ingestDetection(JsonNode)` public method.

- [ ] **Step 1: Write the failing test**

```java
package com.xammer.cloud.service.upwind;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.controller.XamSecureWebhookController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class XamSecureWebhookTest {

    private UpwindSyncService syncService;
    private XamSecureWebhookController controller;

    @BeforeEach
    void setUp() {
        syncService = mock(UpwindSyncService.class);
        controller = new XamSecureWebhookController(syncService, new ObjectMapper());
        controller.setWebhookSecret("s3cr3t");
    }

    @Test
    void rejectsWrongSecret() {
        var resp = controller.ingest("wrong", "{\"id\":\"uwnd-1\"}");
        assertEquals(401, resp.getStatusCodeValue());
        verifyNoInteractions(syncService);
    }

    @Test
    void ingestsSingleDetection() {
        var resp = controller.ingest("s3cr3t", "{\"id\":\"uwnd-1\",\"severity\":\"HIGH\"}");
        assertEquals(200, resp.getStatusCodeValue());
        verify(syncService, times(1)).ingestDetection(any());
    }

    @Test
    void ingestsArrayOfDetections() {
        var resp = controller.ingest("s3cr3t", "[{\"id\":\"a\"},{\"id\":\"b\"}]");
        assertEquals(200, resp.getStatusCodeValue());
        verify(syncService, times(2)).ingestDetection(any());
    }

    @Test
    void missingSecretConfigRejects() {
        controller.setWebhookSecret("");
        var resp = controller.ingest("anything", "{\"id\":\"x\"}");
        assertEquals(401, resp.getStatusCodeValue());
    }
}
```

- [ ] **Step 2: Run to verify fail**

Run: `mvn -f xamops-service/pom.xml test -q -Dtest=XamSecureWebhookTest`
Expected: COMPILATION ERROR

- [ ] **Step 3: Expose ingestDetection on UpwindSyncService**

In `UpwindSyncService`, change the private `upsertDetection(JsonNode, Instant)` usage by adding a public wrapper:

```java
    @Transactional
    public void ingestDetection(JsonNode item) {
        upsertDetection(item, Instant.now());
    }
```

- [ ] **Step 4: Write the controller**

```java
package com.xammer.cloud.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.service.upwind.UpwindSyncService;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Public ingest endpoint for Upwind's real-time detection webhook (vendor=CUSTOM).
 * Auth is a shared secret in the X-Upwind-Secret header (constant-time compare).
 * Registered in Upwind via XamSecureOnboardingController / admin.
 */
@RestController
@RequestMapping("/api/xamsecure/webhook")
@Slf4j
public class XamSecureWebhookController {

    private final UpwindSyncService syncService;
    private final ObjectMapper objectMapper;

    @Setter
    @Value("${upwind.webhook.secret:}")
    private String webhookSecret;

    public XamSecureWebhookController(UpwindSyncService syncService, ObjectMapper objectMapper) {
        this.syncService = syncService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/upwind")
    public ResponseEntity<?> ingest(@RequestHeader(value = "X-Upwind-Secret", required = false) String secret,
                                    @RequestBody String body) {
        if (webhookSecret == null || webhookSecret.isEmpty()
                || secret == null
                || !java.security.MessageDigest.isEqual(
                        secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        webhookSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        }
        try {
            JsonNode json = objectMapper.readTree(body);
            int count = 0;
            if (json.isArray()) {
                for (JsonNode item : json) { syncService.ingestDetection(item); count++; }
            } else if (json.has("id")) {
                syncService.ingestDetection(json); count++;
            }
            return ResponseEntity.ok(Map.of("ingested", count));
        } catch (Exception e) {
            log.error("[Upwind webhook] ingest failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "invalid payload"));
        }
    }
}
```

- [ ] **Step 5: Add config + permit the path**

In `application.properties` add: `upwind.webhook.secret=${UPWIND_WEBHOOK_SECRET:}`
In `SecurityConfig`, add `/api/xamsecure/webhook/**` to the permitAll matchers (the webhook is authenticated by its own shared secret, not a session). Find the existing `.antMatchers(...).permitAll()` / `requestMatchers(...)` chain used for public endpoints (e.g. agent ingest) and add the path there.

- [ ] **Step 6: Run tests**

Run: `mvn -f xamops-service/pom.xml test -q -Dtest=XamSecureWebhookTest`
Expected: 4 tests PASS

- [ ] **Step 7: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/controller/XamSecureWebhookController.java xamops-service/src/main/java/com/xammer/cloud/service/upwind/UpwindSyncService.java xamops-service/src/main/resources/application.properties xamops-service/src/main/java/com/xammer/cloud/config/SecurityConfig.java xamops-service/src/test/java/com/xammer/cloud/service/upwind/XamSecureWebhookTest.java
git commit -m "feat(xamsecure): real-time detection webhook ingest (shared-secret, upsert)"
```

---

### Task 9: Webhook registration (admin)

**Files:**
- Modify: `xamops-service/src/main/java/com/xammer/cloud/controller/XamSecureOnboardingController.java`
- Test: `xamops-service/src/test/java/com/xammer/cloud/service/upwind/XamSecureWebhookRegisterTest.java`

**Interfaces:**
- Consumes: Task 1 `apiClient.createWebhook(JsonNode)`; config `upwind.webhook.secret`, and a new `xamops.public.base-url` for the callback URL.
- Produces: `POST /api/xamsecure/onboarding/register-webhook` (SUPER_ADMIN) → builds a `vendor=CUSTOM` webhook body pointing at our ingest URL with the shared secret as a BEARER token, calls Upwind, returns the created webhook id.

- [ ] **Step 1: Write the failing test**

```java
package com.xammer.cloud.service.upwind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.controller.XamSecureOnboardingController;
import com.xammer.cloud.repository.UpwindAccountConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class XamSecureWebhookRegisterTest {

    private UpwindApiClient apiClient;
    private XamSecureOnboardingController controller;

    @BeforeEach
    void setUp() {
        apiClient = mock(UpwindApiClient.class);
        var svc = mock(XamSecureService.class);
        var connRepo = mock(UpwindAccountConnectionRepository.class);
        controller = new XamSecureOnboardingController(svc, connRepo, apiClient);
        controller.setWebhookSecret("s3cr3t");
        controller.setPublicBaseUrl("https://xamops.example");
    }

    @Test
    void registerWebhookPostsCustomVendorWithCallbackAndSecret() throws Exception {
        JsonNode created = new ObjectMapper().readTree("{\"id\":\"wh-1\"}");
        when(apiClient.createWebhook(any())).thenReturn(created);

        var resp = controller.registerWebhook();
        assertEquals(200, resp.getStatusCodeValue());

        ArgumentCaptor<JsonNode> cap = ArgumentCaptor.forClass(JsonNode.class);
        verify(apiClient).createWebhook(cap.capture());
        JsonNode body = cap.getValue();
        assertEquals("CUSTOM", body.get("vendor").asText());
        assertTrue(body.get("config").get("url").asText().contains("/api/xamsecure/webhook/upwind"));
        assertEquals("s3cr3t", body.get("config").get("authentication").get("token").asText());
    }

    @Test
    void registerWebhookFailsWithoutSecret() {
        controller.setWebhookSecret("");
        var resp = controller.registerWebhook();
        assertEquals(400, resp.getStatusCodeValue());
        verifyNoInteractions(apiClient);
    }
}
```

Note: this changes `XamSecureOnboardingController`'s constructor to take `UpwindApiClient` as a third arg. Update the existing Phase 1 `XamSecureOnboardingTest` construction accordingly (add a mocked `UpwindApiClient`) — the reviewer will flag if missed.

- [ ] **Step 2: Run to verify fail**

Run: `mvn -f xamops-service/pom.xml test -q -Dtest=XamSecureWebhookRegisterTest`
Expected: COMPILATION ERROR

- [ ] **Step 3: Implement**

Add to `XamSecureOnboardingController`: inject `UpwindApiClient apiClient` (constructor 3rd arg), `@Value("${upwind.webhook.secret:}") @Setter String webhookSecret`, `@Value("${xamops.public.base-url:http://localhost:8080}") @Setter String publicBaseUrl`, an `ObjectMapper` field, and:

```java
    @PostMapping("/register-webhook")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<?> registerWebhook() {
        if (webhookSecret == null || webhookSecret.isEmpty()) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error",
                    "upwind.webhook.secret not configured"));
        }
        com.fasterxml.jackson.databind.node.ObjectNode body = objectMapper.createObjectNode();
        body.put("name", "XamOps XamSecure");
        body.put("vendor", "CUSTOM");
        com.fasterxml.jackson.databind.node.ObjectNode config = body.putObject("config");
        config.put("url", publicBaseUrl + "/api/xamsecure/webhook/upwind");
        com.fasterxml.jackson.databind.node.ObjectNode auth = config.putObject("authentication");
        auth.put("type", "BEARER");
        auth.put("token", webhookSecret);
        try {
            com.fasterxml.jackson.databind.JsonNode created = apiClient.createWebhook(body);
            return ResponseEntity.ok(java.util.Map.of("webhookId", created.path("id").asText()));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(java.util.Map.of("error", e.getMessage()));
        }
    }
```

Add `xamops.public.base-url=${XAMOPS_PUBLIC_BASE_URL:http://localhost:8080}` to `application.properties`. Note: the webhook ingest reads the secret from `X-Upwind-Secret`, but here we register it as a BEARER token — the ingest controller (Task 8) checks `X-Upwind-Secret`. **Align them:** update Task 8's ingest to also accept the secret from the `Authorization: Bearer <secret>` header (Upwind CUSTOM webhooks send auth as configured). Simplest: in Task 8's `ingest`, if `X-Upwind-Secret` is null, parse a `Bearer ` token from the `Authorization` header and use that. Add that fallback when implementing Task 8, OR register the webhook with a custom header instead of BEARER if Upwind's CUSTOM config supports `headers[]` (the schema showed a `headers` array). Prefer the `headers` approach: set `config.authentication` to a header `X-Upwind-Secret: <secret>`. Confirm against Upwind docs during Task 9; if unclear, implement the `Authorization: Bearer` fallback in Task 8's controller and note it.

- [ ] **Step 4: Run tests**

Run: `mvn -f xamops-service/pom.xml test -q -Dtest=XamSecureWebhookRegisterTest,XamSecureOnboardingTest`
Expected: all PASS

- [ ] **Step 5: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/controller/XamSecureOnboardingController.java xamops-service/src/main/resources/application.properties xamops-service/src/test/java/com/xammer/cloud/service/upwind/XamSecureWebhookRegisterTest.java xamops-service/src/test/java/com/xammer/cloud/service/upwind/XamSecureOnboardingTest.java
git commit -m "feat(xamsecure): admin endpoint to register CUSTOM detection webhook in Upwind"
```

---

### Task 10: Detection acknowledge/resolve action

**Files:**
- Modify: `xamops-service/src/main/java/com/xammer/cloud/service/upwind/XamSecureService.java`
- Modify: `xamops-service/src/main/java/com/xammer/cloud/controller/XamSecureController.java`
- Modify: `xamops-service/src/test/java/com/xammer/cloud/service/upwind/XamSecureServiceTest.java`
- Modify: `frontend-app/src/hooks/useXamSecureData.ts`
- Modify: `frontend-app/src/components/XamSecure/ThreatsTab.tsx`

**Interfaces:**
- Consumes: Task 1 `apiClient.updateDetectionStatus(upwindId, status)`; `UpwindThreatDetectionRepository`, `resolveAccountIds`.
- Produces: `POST /api/xamsecure/threats/{upwindId}/status` body `{"status":"ARCHIVED"}` → verifies the detection belongs to the caller's accounts, PATCHes Upwind, updates the local row. Frontend: an "Archive" action in the threat drawer/row.

- [ ] **Step 1: Add failing test**

```java
    @Test
    void updateThreatStatusRejectsForeignDetection() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        com.xammer.cloud.domain.UpwindThreatDetection d = new com.xammer.cloud.domain.UpwindThreatDetection();
        d.setUpwindId("uwnd-1"); d.setCloudAccountId("999"); // not owned
        when(detectionRepo.findByUpwindId("uwnd-1")).thenReturn(Optional.of(d));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
            () -> service.updateThreatStatus(1L, "uwnd-1", "ARCHIVED"));
        verify(apiClient, never()).updateDetectionStatus(any(), any());
    }

    @Test
    void updateThreatStatusPatchesAndPersists() {
        when(cloudAccountRepo.findByClientId(1L)).thenReturn(List.of(aws("111")));
        com.xammer.cloud.domain.UpwindThreatDetection d = new com.xammer.cloud.domain.UpwindThreatDetection();
        d.setUpwindId("uwnd-1"); d.setCloudAccountId("111");
        when(detectionRepo.findByUpwindId("uwnd-1")).thenReturn(Optional.of(d));

        service.updateThreatStatus(1L, "uwnd-1", "ARCHIVED");

        verify(apiClient).updateDetectionStatus("uwnd-1", "ARCHIVED");
        org.mockito.ArgumentCaptor<com.xammer.cloud.domain.UpwindThreatDetection> cap =
            org.mockito.ArgumentCaptor.forClass(com.xammer.cloud.domain.UpwindThreatDetection.class);
        verify(detectionRepo).save(cap.capture());
        assertEquals("ARCHIVED", cap.getValue().getStatus());
    }
```

- [ ] **Step 2: Run to verify fail**

Run: `mvn -f xamops-service/pom.xml test -q -Dtest=XamSecureServiceTest`
Expected: COMPILATION ERROR

- [ ] **Step 3: Implement updateThreatStatus**

```java
    @org.springframework.transaction.annotation.Transactional
    public void updateThreatStatus(Long clientId, String upwindId, String status) {
        UpwindThreatDetection d = detectionRepo.findByUpwindId(upwindId)
                .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("Not found"));
        List<String> ids = resolveAccountIds(clientId, null);
        if (d.getCloudAccountId() == null || !ids.contains(d.getCloudAccountId())) {
            throw new org.springframework.security.access.AccessDeniedException("Detection not accessible");
        }
        apiClient.updateDetectionStatus(upwindId, status);
        d.setStatus(status);
        detectionRepo.save(d);
    }
```

- [ ] **Step 4: Add controller endpoint**

```java
    @PostMapping("/threats/{upwindId}/status")
    public ResponseEntity<?> updateThreatStatus(@AuthenticationPrincipal ClientUserDetails user,
                                                @PathVariable String upwindId,
                                                @RequestBody Map<String, String> body) {
        String status = body.getOrDefault("status", "ARCHIVED");
        xamSecureService.updateThreatStatus(user.getClientId(), upwindId, status);
        return ResponseEntity.ok(Map.of("status", status));
    }
```

Add `import java.util.Map;` if missing.

- [ ] **Step 5: Frontend action**

In `useXamSecureData.ts` add:
```typescript
  const archiveThreat = useCallback(async (upwindId: string) => {
    const res = await apiClient(`/api/xamsecure/threats/${encodeURIComponent(upwindId)}/status`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ status: 'ARCHIVED' }),
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    await loadThreats();
  }, [loadThreats]);
```
Return `archiveThreat`. In `ThreatsTab.tsx`, accept an `onArchive?: (upwindId: string) => void` prop; in the `FindingDrawer` for a selected threat add an "Archive" button (grey, `bg-gray-900 text-white`) that calls `onArchive(selected.upwindId)` then closes. Pass `onArchive={archiveThreat}` from `XamSecurePage`. (If FindingDrawer has no footer slot, render the button above the `<pre>` inside ThreatsTab's drawer usage by wrapping — keep it minimal, match existing button styles.)

- [ ] **Step 6: Run backend tests + tsc**

Run: `mvn -f xamops-service/pom.xml test -q -Dtest=XamSecureServiceTest`
Then: `cd frontend-app && npx tsc --noEmit`
Expected: all PASS / no new errors.

- [ ] **Step 7: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/service/upwind/XamSecureService.java xamops-service/src/main/java/com/xammer/cloud/controller/XamSecureController.java xamops-service/src/test/java/com/xammer/cloud/service/upwind/XamSecureServiceTest.java frontend-app/src/hooks/useXamSecureData.ts frontend-app/src/components/XamSecure/ThreatsTab.tsx frontend-app/src/pages/XamSecurePage.tsx
git commit -m "feat(xamsecure): acknowledge/resolve (archive) threat detections"
```

---

### Task 11: Full verification + live smoke test

**Files:** none (verification).

- [ ] **Step 1: Full backend suite**

Run: `mvn -f xamops-service/pom.xml test -q`
Expected: BUILD SUCCESS (all Phase 1 + Phase 2 tests).

- [ ] **Step 2: Frontend build + tests**

Run: `cd frontend-app && npm run build && npm run test`
Expected: both succeed.

- [ ] **Step 3: Live smoke test (local Postgres + Redis + real Upwind creds)**

```bash
set -a; source .env.local; set +a
export SPRING_PROFILES_ACTIVE=local UPWIND_SYNC_ENABLED=true
export UPWIND_WEBHOOK_SECRET=localtestsecret
mvn -f xamops-service/pom.xml spring-boot:run
```
After boot + one full-sync cycle, verify (demo user login as in Phase 1 verification via curl to :8080):
- `GET /api/xamsecure/inventory` → 200, `totalElements > 0` for a client owning account `605134457560` (query the DB directly if using the client-2 demo user, which correctly returns empty).
- `GET /api/xamsecure/api-security` → 200 (list; may be empty if no API traffic observed).
- Webhook: `curl -s -X POST http://localhost:8080/api/xamsecure/webhook/upwind -H 'X-Upwind-Secret: localtestsecret' -H 'Content-Type: application/json' -d '{"id":"uwnd-smoke","severity":"HIGH","title":"smoke","resource":{"cloud_account_id":"605134457560"}}'` → `{"ingested":1}`; confirm row in `upwind_threat_detection`.
- Confirm PATCH path: the smoke test should confirm RestTemplate supports PATCH (Task 1 note). If it throws "Invalid HTTP method: PATCH", switch the RestTemplate factory to `HttpComponentsClientHttpRequestFactory` and re-run.

- [ ] **Step 4: Final commit + push**

```bash
git pull --rebase origin main
git push origin main
```

---

## Deferred to Phase 3 (credential-scope gated — see parity matrix)
- Events timeline (`GET /v1/events` → 401; needs scope).
- Automated cloud-account onboarding (`POST /v1/cloud-accounts` → 401; needs scope). Phase 1 admin-registered fallback remains.
- Identity/CIEM tab (needs response-shape verification).
- Threat-policy management UI, custom rules, SBOM explorer, compliance-framework CRUD.
