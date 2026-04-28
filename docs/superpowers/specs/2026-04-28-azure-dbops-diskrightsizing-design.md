# Azure DbOps & Disk Rightsizing — Design Spec

**Date:** 2026-04-28
**Status:** Approved

---

## Overview

Replicate the AWS (RDS / EBS) and GCP (Cloud SQL / Persistent Disks) features for Azure:

1. **Azure DbOps** — cost & performance optimization for Azure SQL Database, SQL Managed Instance, PostgreSQL Flexible Server, and MySQL Flexible Server
2. **Azure Disk Rightsizing** — Managed Disk optimization with XamOps disk agent support for real filesystem utilization

Both features mirror the GCP implementations exactly in structure, reuse all existing shared DTOs and domain entities, and follow the same caching and agent patterns already in place.

---

## 1. Azure DbOps

### Backend

**Controller:** `controller/azure/AzureDbOpsController.java`
- Base path: `/api/dbops/azure/{subscriptionId}`
- Endpoints (identical signature to `GcpDbOpsController`):
  - `GET /{subscriptionId}/inventory`
  - `GET /{subscriptionId}/idle`
  - `GET /{subscriptionId}/rightsizing`
  - `GET /{subscriptionId}/costs`
  - `GET /{subscriptionId}/snapshots`
  - `GET /{subscriptionId}/security`
  - `GET /{subscriptionId}/summary`
  - `GET /{subscriptionId}/slow-queries`
  - `GET /{subscriptionId}/metrics`
  - `GET /{subscriptionId}/agent-instance-metrics`
  - `GET /{subscriptionId}/agent-query-stats`
  - `GET /{subscriptionId}/agent-table-sizes`
  - `GET /{subscriptionId}/agent-timeseries`
  - `GET /{subscriptionId}/agent-index-usage`
  - `GET /{subscriptionId}/agent-table-activity`
  - `GET /{subscriptionId}/agent-vacuum-stats`
  - `GET /{subscriptionId}/agent-user-activity`
  - `GET /{subscriptionId}/instance/{instanceId}/timeseries`

**Service:** `service/azure/AzureDbOpsService.java`
- Caching: 3-tier (Redis → CachedData DB → Live API), same TTLs as GCP
- Database service coverage:
  - **Azure SQL Database** via `AzureResourceManager.sqlServers().databases()`
  - **Azure SQL Managed Instance** via `AzureResourceManager.sqlManagedInstances()`
  - **PostgreSQL Flexible Server** via Azure Management REST (`2023-06-01-preview`)
  - **MySQL Flexible Server** via Azure Management REST (`2023-06-30`)
- **Metrics:** Azure Monitor via `MonitorManager` — CPU percentage, active connections (7-day averages)
- **Costs:** Azure Cost Management API — filtered to DB service categories
- **Snapshots/Backups:** Long-term retention backups older than 30 days
- **Security checks:** Public network access, TLS enforcement, backup enabled, geo-redundant backup
- **Idle detection:** avg CPU ≤ 5% AND avg connections ≤ 1 over 7 days
- **Rightsizing:** avg CPU ≤ 10% with active connections — recommend one tier down
- **Agent infrastructure:** Reuses `DbAgentConfig`, `DbAgentMetricsHistory`, `DbAgentDataCache` tables — keyed by `account_id` (same as AWS/GCP)

**Reused DTOs (no changes):**
`DbInstanceDto`, `DbIdleInstanceDto`, `DbRightsizingDto`, `DbCostDto`, `DbSnapshotWasteDto`, `DbSecurityCheckDto`, `DbSavingsSummaryDto`, `SlowQueriesResponseDto`, `DbMetricsDto`, `DbInstanceTimeseriesDto`, all `Agent*Dto` types.

### Frontend

**Hook:** `hooks/useAzureDbOpsData.ts`
- Mirrors `useGcpDbOpsData.ts` exactly — same state shape, localStorage cache key prefix `azure_dbops_`, API calls to `/api/dbops/azure/{subscriptionId}`

**Page:** `pages/AzureDataOpsPage.tsx`
- Mirrors `GcpDataOpsPage.tsx` — same tabs: Summary | Inventory | Idle | Rightsizing | Costs | Snapshots | Security | Slow Queries | Agent Stats
- **UI design language (DESIGN.md — Claude design system):**
  - Summary cards use warm cream surface with coral left-border accent and dark navy icon gradients
  - Tab bar uses `category-tab` / `category-tab-active` tokens (surface-card bg, ink text when active)
  - Tables use `surface-card` row hover, hairline dividers
  - "Install Agent" button: `button-secondary` style (canvas bg, hairline border)
  - Status badges use `badge-pill` (surface-card) and `badge-coral` for alerts/warnings
  - Dark detail drawers use `surface-dark` / `surface-dark-elevated` (product-mockup-card-dark pattern)
  - Title typography: `title-lg` (StyreneB 22px/500) for section headings

**Route:** `/azure-dataops`

---

## 2. Azure Disk Rightsizing

### Backend

**Controller:** `controller/azure/AzureDiskRightsizingController.java`
- Base path: `/api/azure/disks`
- Endpoints (mirrors `GcpDiskRightsizingController`):
  - `GET /volumes/{accountId}` — all managed disks
  - `GET /recommendations/{accountId}` — filtered: non-NONE recommendations
  - `POST /action/{accountId}` — apply EXPAND / DELETE_UNATTACHED
  - `GET /policies/{accountId}` — disk policies (cloudProvider=AZURE)
  - `POST /policies/{accountId}` — create/update policy
  - `GET /history/{accountId}` — action history (reuses `EbsVolumeAction`)

**Service:** `service/azure/AzureDiskRightsizingService.java`
- Lists all Azure Managed Disks via `AzureResourceManager.disks().list()`
- Disk fields: name, resourceGroup, location (region), diskSizeGB, sku (Premium_LRS, Standard_LRS, StandardSSD_LRS, UltraSSD_LRS), managedBy (attached VM), diskState
- **Cost estimation:** Azure Managed Disk pricing per GB/month by SKU (approximate):
  - `Premium_LRS`: $0.135/GB, `StandardSSD_LRS`: $0.075/GB, `Standard_LRS`: $0.040/GB, `UltraSSD_LRS`: $0.125/GB
- **Recommendations:**
  - `DELETE_UNATTACHED` — disk not attached to any VM (`managedBy == null`)
  - `SHRINK` — agent reports < 25% filesystem usage
  - `EXPAND` — agent reports > 80% filesystem usage
  - `NONE` — otherwise
- **Agent enrichment:** Same pattern as GCP — cache key `azure-disk-agent:{subscriptionId}:{vmName}`, falls back to heuristics when no agent data
- **Reuses:** `DiskPolicy` (cloudProvider="AZURE"), `EbsVolumeAction` for history, `RedisCacheService`

**Agent install script:** `resources/scripts/azure_disk_agent_install.sh`
- Mirrors `gcp_disk_agent_install.sh`
- Uses `df -h` to report filesystem utilization
- Pushes to XamOps API with subscription-level auth token

### Frontend

**Hook:** `hooks/useAzureDiskRightsizingData.ts`
- Mirrors `useGcpDiskRightsizingData.ts` — API calls to `/api/azure/disks/`, same state shape

**Page:** `pages/AzureDiskRightsizingPage.tsx`
- Mirrors `GcpDiskRightsizingPage.tsx` — tabs: Overview | History
- **UI design language (DESIGN.md — Claude design system):**
  - Recommendation badges: `badge-coral` for DELETE_UNATTACHED (error tone), `badge-pill` amber-tinted for SHRINK, blue-tinted for EXPAND
  - Table rows: hairline dividers, surface-card hover state
  - Action confirm modal: surface-dark overlay with `product-mockup-card-dark` panel
  - "Install Agent" panel: `code-window-card` pattern — dark navy bg with monospace install command
  - Filter dropdowns: `text-input` style (canvas bg, hairline border, rounded-md)
  - Page title section: title typography hierarchy from DESIGN.md

**Route:** `/azure-disk-rightsizing`

---

## 3. Routing (App.tsx)

Two new lazy-loaded routes added to `App.tsx`:
```tsx
const AzureDataOpsPage        = lazy(() => import('./pages/AzureDataOpsPage'));
const AzureDiskRightsizingPage = lazy(() => import('./pages/AzureDiskRightsizingPage'));

<Route path="/azure-dataops"         element={<AzureDataOpsPage />} />
<Route path="/azure-disk-rightsizing" element={<AzureDiskRightsizingPage />} />
```

---

## 4. Shared Infrastructure (no changes needed)

| Entity | Table | Reused as-is |
|--------|-------|-------------|
| `DiskPolicy` | `disk_policy` | `cloudProvider="AZURE"` already supported |
| `EbsVolumeAction` | `ebs_volume_action` | Reused by GCP, reused by Azure too |
| `DbAgentConfig` | `db_agent_config` | Keyed by account — works for Azure |
| `DbAgentMetricsHistory` | `db_agent_metrics_history` | Same |
| `DbAgentDataCache` | `db_agent_data_cache` | Same |
| `CachedData` | `cached_data` | Same 3-tier cache pattern |
| `RedisCacheService` | Redis | Same |
| `AzureClientProvider` | — | Already exists, provides `AzureResourceManager` |

---

## 5. Azure SDK Dependencies

All already present in `pom.xml` via `azure-resourcemanager` 2.31.0:
- `azure-resourcemanager` (SQL, managed disks, monitor) — confirmed from existing Azure services
- `azure-resourcemanager-monitor` — for Azure Monitor metrics
- HTTP REST calls for PostgreSQL/MySQL Flexible Server (use `AzureClientProvider.getCredential()` + OkHttp / RestTemplate)

---

## Key Design Decisions

1. **All Azure managed DB types in one service** — single `AzureDbOpsService` handles SQL DB, SQL MI, PostgreSQL FS, and MySQL FS. Each produces the same `DbInstanceDto` with `engine` field set to identify the type.
2. **Disk SKU maps to disk type** — Azure SKU names (Premium_LRS etc.) replace GCP's pd-standard/pd-ssd as the `diskType` field in the disk DTO.
3. **Agent pattern identical to GCP** — no new infrastructure; same Redis keys, same agent data tables.
4. **No new DB migrations needed** — all entity tables already support Azure via the `cloudProvider` discriminator field.
