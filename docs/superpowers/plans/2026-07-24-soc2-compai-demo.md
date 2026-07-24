# SOC2 comp.ai Demo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up a self-hosted comp.ai instance (seeded with one fictional company, "Re Digital", mid-SOC2-audit) on AWS EC2, and surface it through a brand-new native XamOps nav area — Overview, Controls, Policies, Evidence & Tasks, Personnel, Vendors, plus a Trust Center link-out — so the Monday customer demo shows SOC2 driven through the XamOps UI.

**Architecture:** EC2 runs comp.ai's Docker stack (Postgres + `apps/api` NestJS + `apps/app` Next.js, used only for content authoring + `apps/portal` Next.js, the public Trust Center). `xamops-service` gets a new `compai` package that calls comp.ai's `apps/api` server-to-server via `X-API-Key` (comp.ai's built-in org-scoped API key auth, confirmed in `HybridAuthGuard`), normalizing responses into XamOps-owned DTOs. `frontend-app` gets one new lazy-loaded page with 6 tabs, following the exact pattern already used by `XamSecurePage`/`ComplianceOpsPage`.

**Tech Stack:** Docker Compose, PostgreSQL 14+, NestJS (comp.ai, unmodified upstream), Spring Boot / Java (Lombok `@Data` DTOs, `RestTemplate`, `RedisCacheService`), React/TypeScript (Vite, `apiClient`, `useTopbar`).

## Global Constraints

- Theme: black/white/grey only, no purple/blue gradients, no emojis in UI (per `CLAUDE.md`).
- New frontend page must: use a Redis-backed caching hook (backend side, since this is read-mostly external data), wire the Topbar title via `useTopbar()`, register its route in `App.tsx`, use `apiClient` from `utils/apiClient` for all calls.
- Same-service-same-icon rule doesn't apply here (no cloud-provider icons involved) but existing SVG icon set / `@iconify/react` `mdi:*` icons should be reused, not new icon files.
- No live integrations, no Trigger.dev/Resend-driven workflows, no device agent — this is a static, seeded demo (per `docs/superpowers/specs/2026-07-24-soc2-compai-demo-design.md`).
- Backend calls to comp.ai are server-to-server only (`X-API-Key` header held in `xamops-service`); no comp.ai login/session is ever exposed to XamOps users.
- Run all backend commands with `mvn -f xamops-service/pom.xml ...` and all frontend commands from `cd frontend-app` — never bare `mvn`/`npm` from the repo root (per `CLAUDE.md`).

---

## Phase 1 — Infrastructure: EC2 + comp.ai deployment

These tasks are executed by the user (no remote access from this session). Each task is one complete copy-pasteable block, per the project's established remote-debugging convention.

### Task 1: Provision EC2 and install Docker

**Files:** none (infrastructure only)

- [ ] **Step 1: Launch the instance**

Launch an EC2 instance: Ubuntu 22.04 LTS, **t3.large** (2 vCPU / 8GB — comp.ai runs 3 Next.js/Node processes + Postgres + a NestJS API concurrently), 30GB gp3 disk. Open inbound security group rules for: 22 (SSH, your IP only), 80/443 (HTTP/HTTPS, your IP or a small allowlist — this is a demo box, not public).

- [ ] **Step 2: Install Docker + Compose**

SSH in, then run:

```bash
sudo apt-get update && sudo apt-get install -y ca-certificates curl gnupg
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
sudo usermod -aG docker $USER
```

Log out and back in (so the `docker` group membership takes effect), then verify:

```bash
docker --version && docker compose version
```

Expected: both print version numbers with no permission errors.

- [ ] **Step 3: Confirm done**

Reply with the output of `docker --version` before moving to Task 2.

---

### Task 2: Clone comp.ai and configure env files (no Trigger.dev/Resend)

**Files:** none (lives entirely on the EC2 box, outside this repo)

- [ ] **Step 1: Clone and inspect env examples**

```bash
git clone https://github.com/trycompai/comp.git ~/comp
cd ~/comp
cat packages/db/.env.example
cat apps/app/.env.example
cat apps/portal/.env.example
```

Read the three example files — note every variable name so Step 2 doesn't miss one silently.

- [ ] **Step 2: Create the three env files**

```bash
cp packages/db/.env.example packages/db/.env
cp apps/app/.env.example apps/app/.env
cp apps/portal/.env.example apps/portal/.env
```

- [ ] **Step 3: Fill in the required values**

Edit `packages/db/.env` — set `DATABASE_URL` to the Postgres container's connection string (Task 3 creates that container on the same Docker network, hostname `db`):

```
DATABASE_URL=postgresql://compai:REPLACE_WITH_A_STRONG_PASSWORD@db:5432/compai?schema=public
```

Edit `apps/app/.env` — set:

```
DATABASE_URL=postgresql://compai:REPLACE_WITH_A_STRONG_PASSWORD@db:5432/compai?schema=public
AUTH_SECRET=<run: openssl rand -hex 32>
SECRET_KEY=<run: openssl rand -hex 32>
BETTER_AUTH_URL=http://<EC2_PUBLIC_IP_OR_DOMAIN>:3000
NEXT_PUBLIC_BETTER_AUTH_URL=http://<EC2_PUBLIC_IP_OR_DOMAIN>:3000
NEXT_PUBLIC_SELF_HOSTED=true
NEXT_PUBLIC_PORTAL_URL=http://<EC2_PUBLIC_IP_OR_DOMAIN>:3002
REVALIDATION_SECRET=<run: openssl rand -hex 32>
RESEND_API_KEY=re_placeholder_not_used
TRIGGER_SECRET_KEY=tr_placeholder_not_used
```

`NEXT_PUBLIC_SELF_HOSTED=true` auto-approves the org and skips the Stripe/payment flow. `RESEND_API_KEY`/`TRIGGER_SECRET_KEY` are placeholders — the Dockerfile builds with `SKIP_ENV_VALIDATION=true`, so these only error if an actual email-send or workflow-trigger code path fires, which this demo never exercises (per the design's non-goals).

Edit `apps/portal/.env` similarly:

```
DATABASE_URL=postgresql://compai:REPLACE_WITH_A_STRONG_PASSWORD@db:5432/compai?schema=public
BETTER_AUTH_SECRET=<run: openssl rand -hex 32>
RESEND_API_KEY=re_placeholder_not_used
```

- [ ] **Step 4: Commit no secrets anywhere**

Confirm `.env` files are gitignored (they are, per comp.ai's `.gitignore`) — run `git status` inside `~/comp` and confirm none of the three `.env` files show as trackable.

---

### Task 3: Add Postgres, migrate, seed baseline data

**Files:** `~/comp/docker-compose.override.yml` (new, on the EC2 box only)

- [ ] **Step 1: Add a Postgres service via an override file**

comp.ai's root `docker-compose.yml` expects an external Postgres. For a self-contained demo box, add one:

```bash
cat > ~/comp/docker-compose.override.yml <<'EOF'
services:
  db:
    image: postgres:16
    restart: unless-stopped
    environment:
      POSTGRES_USER: compai
      POSTGRES_PASSWORD: REPLACE_WITH_A_STRONG_PASSWORD
      POSTGRES_DB: compai
    volumes:
      - compai_pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U compai"]
      interval: 10s
      timeout: 5s
      retries: 5
volumes:
  compai_pgdata:
EOF
```

Use the **same password** you put in the three `.env` files in Task 2.

- [ ] **Step 2: Build the images**

```bash
cd ~/comp
export BETTER_AUTH_URL="http://<EC2_PUBLIC_IP_OR_DOMAIN>:3000"
export BETTER_AUTH_URL_PORTAL="http://<EC2_PUBLIC_IP_OR_DOMAIN>:3002"
docker compose build --no-cache
```

Expected: three image builds (migrator/app, portal) complete with no error exit code. This step takes a while (Next.js production builds) — expect 10-20 minutes on a t3.large.

- [ ] **Step 3: Start Postgres, then migrate and seed**

```bash
docker compose up -d db
docker compose ps db
```

Expected: `db` shows `healthy` within ~30s. Then:

```bash
docker compose run --rm migrator
docker compose run --rm seeder
```

Expected: migrator applies Prisma migrations with no error; seeder logs baseline framework/control-library data being inserted (this is comp.ai's generic SOC2/ISO27001/etc. control catalog — Task 6-11 layer the Re Digital-specific org data on top of it).

- [ ] **Step 4: Confirm done**

```bash
docker compose exec db psql -U compai -d compai -c "\dt" | head -20
```

Expected: a list of tables (organizations, controls, policies, people, vendors, etc.) — confirms the schema migrated successfully.

---

### Task 4: Start app/api/portal and verify health

**Files:** `~/comp/docker-compose.override.yml` (extend from Task 3), plus a new standalone compose file for `apps/api` (not in the root compose)

- [ ] **Step 1: Start app + portal**

```bash
cd ~/comp
docker compose up -d app portal
docker compose ps
```

Expected: `app` and `portal` show `healthy` within ~60s (root `docker-compose.yml` already defines healthchecks hitting `/api/health` and `/`).

- [ ] **Step 2: Build and run apps/api separately**

`apps/api` has its own `Dockerfile`/`docker-compose.yml` (not part of the root stack). Configure its env and start it on the same Docker network:

```bash
cd ~/comp/apps/api
cp .env.example .env
```

Edit `apps/api/.env` — set `DATABASE_URL` to the same Postgres connection string used in Task 2, plus whatever `BETTER_AUTH_SECRET`/service-token values the `.env.example` calls for (copy the same `AUTH_SECRET` value used in `apps/app/.env` so session validation lines up). Then:

```bash
docker network connect comp_default $(docker compose -f docker-compose.yml ps -q db 2>/dev/null) 2>/dev/null || true
docker compose build --no-cache
docker compose up -d
docker compose ps
```

Expected: the API container is healthy and reachable.

- [ ] **Step 3: Verify all four services respond**

```bash
curl -sf http://localhost:3000/api/health && echo " app OK"
curl -sf http://localhost:3002/ -o /dev/null -w "%{http_code}\n" 
curl -sf http://localhost:<API_PORT>/health && echo " api OK"
```

Expected: `app OK`, an HTTP 200, and `api OK` — replace `<API_PORT>` with whatever port `apps/api`'s compose file exposes (check `apps/api/docker-compose.yml`'s `ports:` section).

- [ ] **Step 4: Confirm done**

Reply with all three curl outputs before moving to Task 5.

---

### Task 5: Reverse proxy + subdomains

**Files:** `/etc/caddy/Caddyfile` (new, on the EC2 box)

- [ ] **Step 1: Install Caddy**

```bash
sudo apt-get install -y debian-keyring debian-archive-keyring apt-transport-https
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt-get update && sudo apt-get install -y caddy
```

- [ ] **Step 2: Configure subdomains**

If you have DNS control over a domain, point three A records at the EC2 IP: `compai-app.<yourdomain>`, `compai-api.<yourdomain>`, `trust.<yourdomain>`. Then:

```bash
sudo tee /etc/caddy/Caddyfile <<'EOF'
compai-app.<yourdomain> {
    reverse_proxy localhost:3000
}
compai-api.<yourdomain> {
    reverse_proxy localhost:<API_PORT>
}
trust.<yourdomain> {
    reverse_proxy localhost:3002
}
EOF
sudo systemctl restart caddy
```

Caddy auto-provisions Let's Encrypt TLS for each subdomain. If you don't have a domain to point at this box, skip Caddy and use `http://<EC2_PUBLIC_IP>:3000` / `:<API_PORT>` / `:3002` directly for the rest of this plan — everywhere `compai-api.<yourdomain>` appears below, substitute `http://<EC2_PUBLIC_IP>:<API_PORT>`.

- [ ] **Step 3: Confirm done**

```bash
curl -sI https://compai-app.<yourdomain>/api/health | head -1
```

Expected: `HTTP/2 200`.

---

## Phase 2 — Org + content seeding

Executed by the user via comp.ai's real UI (`apps/app`), following the content list below. Claude does not have access to author this directly (no browser session on the EC2 box), but reviews screenshots/exports on request.

### Task 6: Create the Re Digital org and API key

**Files:** none

- [ ] **Step 1: Sign up and create the org**

Visit `https://compai-app.<yourdomain>` (or `http://<EC2_IP>:3000`), sign up, create an organization named **Re Digital**. Since `NEXT_PUBLIC_SELF_HOSTED=true`, this auto-approves without a payment flow.

- [ ] **Step 2: Enable the SOC 2 framework**

In the org's Frameworks section, add **SOC 2** (Type II) as the active framework — this seeds the standard control library for the org, which Task 7 customizes.

- [ ] **Step 3: Generate a server-to-server API key**

In org settings → API Keys, create a key scoped to read `control`, `policy`, `task`, `people`, `vendor`, `framework` (comp.ai's `RequirePermission` decorators gate each resource independently — grant read access to all six). Name it `xamops-proxy`.

- [ ] **Step 4: Confirm done**

Save the API key value and the org's `organizationId` (visible in the org settings URL or API key detail page) — Phase 3 needs both. Do not commit either to git; they go into `xamops-service`'s environment variables (Task 16).

---

### Task 7: Populate ~40 SOC 2 controls with mixed status

**Files:** none

- [ ] **Step 1: Enter the following controls**

Work through comp.ai's seeded SOC 2 control library (from Task 6 Step 2) and set status + owner for each. Use this exact set — it mirrors AICPA's Trust Services Criteria structure (Common Criteria CC1–CC9, plus Availability and Confidentiality, the two most commonly in-scope supplemental categories for a SaaS company) so it reads as authentically SOC 2, not generic:

| # | Control | Category | Status | Owner role |
|---|---|---|---|---|
| 1 | Board oversight of internal control | CC1 – Control Environment | Passing | CEO |
| 2 | Management establishes structure, authority, responsibility | CC1 | Passing | CEO |
| 3 | Commitment to competence in hiring | CC1 | Passing | Head of People |
| 4 | Accountability for internal control responsibilities | CC1 | Passing | CEO |
| 5 | Board independence from management | CC1 | Passing | CEO |
| 6 | Risk assessment process defined | CC2 – Communication & Information | Passing | CISO |
| 7 | Internal communication of control responsibilities | CC2 | Passing | CISO |
| 8 | External communication (customers, regulators) | CC2 | In Progress | CISO |
| 9 | Objectives specified clearly enough to assess risk | CC3 – Risk Assessment | Passing | CISO |
| 10 | Risk identification across the entity | CC3 | Passing | CISO |
| 11 | Fraud risk considered in risk assessment | CC3 | In Progress | CFO |
| 12 | Changes that impact controls are identified | CC3 | Passing | Head of Eng |
| 13 | Ongoing/separate evaluations of controls | CC4 – Monitoring Activities | Passing | CISO |
| 14 | Deficiencies communicated and remediated | CC4 | Passing | CISO |
| 15 | Control activities selected to mitigate risk | CC5 – Control Activities | Passing | Head of Eng |
| 16 | Technology general controls selected | CC5 | Passing | Head of Eng |
| 17 | Policies establish expectations, procedures put them into action | CC5 | Passing | CISO |
| 18 | Logical access security (least privilege) | CC6 – Logical & Physical Access | Passing | Head of Eng |
| 19 | New user access provisioning | CC6 | Passing | IT Admin |
| 20 | User access deprovisioning on termination | CC6 | Needs Attention | IT Admin |
| 21 | MFA enforced for privileged access | CC6 | Passing | IT Admin |
| 22 | Physical access to facilities restricted | CC6 | Passing | Office Manager |
| 23 | Encryption of data at rest | CC6 | Passing | Head of Eng |
| 24 | Encryption of data in transit | CC6 | Passing | Head of Eng |
| 25 | System monitoring to detect anomalies | CC7 – System Operations | Passing | CISO |
| 26 | Incident response plan tested | CC7 | In Progress | CISO |
| 27 | Vulnerability scanning cadence | CC7 | Passing | Head of Eng |
| 28 | Backup and recovery procedures | CC7 | Passing | Head of Eng |
| 29 | Change management process for infra/code | CC8 – Change Management | Passing | Head of Eng |
| 30 | Change testing prior to production release | CC8 | Passing | Head of Eng |
| 31 | Emergency change procedure documented | CC8 | Needs Attention | Head of Eng |
| 32 | Risk mitigation for vendor/business partners | CC9 – Risk Mitigation | In Progress | CFO |
| 33 | Business continuity plan maintained | CC9 | Passing | CFO |
| 34 | Availability commitments monitored (uptime SLOs) | A1 – Availability | Passing | Head of Eng |
| 35 | Capacity planning for demand | A1 | Passing | Head of Eng |
| 36 | Environmental protections (data center) | A1 | Passing | Head of Eng |
| 37 | Confidential information identified and classified | C1 – Confidentiality | Passing | CISO |
| 38 | Confidential information disposal procedures | C1 | In Progress | CISO |
| 39 | Access to confidential information restricted | C1 | Passing | Head of Eng |
| 40 | Confidentiality commitments in customer contracts | C1 | Passing | Legal/CFO |

Aim for the status mix shown (roughly 32 Passing / 5 In Progress / 3 Needs Attention) — this is what makes the "mid-audit" narrative land instead of a suspicious 100%.

- [ ] **Step 2: Confirm done**

In comp.ai's Controls list, filter by status and confirm counts roughly match: ~32 Passing, ~5 In Progress, ~3 Needs Attention, 40 total.

---

### Task 8: Author 8-10 policies

**Files:** none

- [ ] **Step 1: Create these policies**

Use comp.ai's AI policy generation (if an LLM key is configured on the EC2 box) or draft directly. Titles, matched to the controls above:

1. Access Control Policy (→ CC6 controls)
2. Incident Response Policy (→ CC7 control #26)
3. Change Management Policy (→ CC8 controls)
4. Data Retention & Disposal Policy (→ C1 control #38)
5. Vendor Management Policy (→ CC9 control #32)
6. Business Continuity & Disaster Recovery Policy (→ CC9 control #33, A1 control #34)
7. Risk Assessment Policy (→ CC3 controls)
8. Employee Onboarding & Offboarding Policy (→ CC6 control #20)
9. Encryption Policy (→ CC6 controls #23, #24)
10. Acceptable Use Policy (→ CC1/CC2 general)

Set each to **Published** status except one or two left **In Review** (again, mid-audit realism — not every policy is done).

- [ ] **Step 2: Link each policy to its control(s)**

Use comp.ai's control-detail "Link policies" action (`POST /v1/controls/:id/policies/link`, exposed in the UI) so drilling into a control shows its policy attached.

- [ ] **Step 3: Confirm done**

Open 2-3 controls at random in the UI and confirm each shows a linked, readable policy document (not blank).

---

### Task 9: Add personnel (5-8 employees) with mixed compliance status

**Files:** none

- [ ] **Step 1: Add these people**

| Name | Role | Onboarding | Training | Device compliance |
|---|---|---|---|---|
| Priya Sharma | CEO | Complete | Complete | Compliant |
| Marcus Webb | Head of Engineering | Complete | Complete | Compliant |
| Elena Vasquez | CISO | Complete | Complete | Compliant |
| Tom Reyes | IT Admin | Complete | Complete | Compliant |
| Sarah Kim | Head of People | Complete | Complete | Compliant |
| David Chen | Backend Engineer | Complete | **Pending** | Compliant |
| Anika Patel | Sales Lead | Complete | Complete | **Pending** |
| Jordan Lee | New hire (Support) | **In Progress** | **Pending** | **Pending** |

- [ ] **Step 2: Confirm done**

Confirm the People list shows the mix above (mostly compliant, 2-3 rows with a pending item) — not entirely green.

---

### Task 10: Add vendors (4-6) with risk scores

**Files:** none

- [ ] **Step 1: Add these vendors**

| Vendor | Category | Risk level | Review status |
|---|---|---|---|
| AWS | Cloud infrastructure | Low | Reviewed |
| Stripe | Payments | Low | Reviewed |
| Segment | Analytics | Medium | Reviewed |
| Zendesk | Customer support | Low | Reviewed |
| A regional marketing SaaS tool | Marketing | Medium | **Review overdue** |

- [ ] **Step 2: Confirm done**

Confirm the Vendors list shows one vendor flagged for overdue review — gives the demo a "here's what we're tracking" moment rather than everything looking closed out.

---

### Task 11: Attach evidence to controls and brand the Trust Center

**Files:** none

- [ ] **Step 1: Attach at least one evidence artifact per control**

For each of the 40 controls, attach at least one evidence item (a screenshot, a config export, or a linked policy document counts as evidence in comp.ai's model). Controls already linked to a policy in Task 8 can reuse that policy as their evidence; the remainder need something control-specific (e.g. an MFA enforcement screenshot for control #21, a backup job log for control #28).

- [ ] **Step 2: Brand the Trust Center**

In `apps/portal`'s org settings, set the organization name/logo to **Re Digital** and confirm the public trust page at `https://trust.<yourdomain>` (or `http://<EC2_IP>:3002`) shows the SOC 2 badge and a summary of the 40 controls.

- [ ] **Step 3: Confirm done**

Load the trust portal URL in an incognito window (no comp.ai login) and confirm it renders publicly with Re Digital's name and SOC 2 status visible.

---

## Phase 3 — `xamops-service` backend proxy

Executed by Claude. Follows the exact pattern already established by `com.xammer.cloud.service.upwind.UpwindApiClient` / `XamSecureService` / `XamSecureController` (read `xamops-service/src/main/java/com/xammer/cloud/service/upwind/UpwindApiClient.java` and `.../XamSecureService.java` for the template being mirrored).

### Task 12: Capture a live API contract fixture

**Files:**
- Create: `xamops-service/src/test/resources/compai/controls-list.json`
- Create: `xamops-service/src/test/resources/compai/policies-list.json`
- Create: `xamops-service/src/test/resources/compai/tasks-list.json`
- Create: `xamops-service/src/test/resources/compai/people-list.json`
- Create: `xamops-service/src/test/resources/compai/vendors-list.json`
- Create: `xamops-service/src/test/resources/compai/frameworks-list.json`

**Interfaces:**
- Produces: six JSON fixture files with comp.ai's real response shapes, consumed by Task 14's deserialization tests.

- [ ] **Step 1: Curl each endpoint against the deployed instance**

Using the API key from Task 6 Step 3 and org ID from Task 6 Step 4:

```bash
API_KEY="<the xamops-proxy key from Task 6>"
BASE="https://compai-api.<yourdomain>/v1"

curl -s -H "X-API-Key: $API_KEY" "$BASE/controls?perPage=100" | tee /tmp/controls-list.json | head -c 500
curl -s -H "X-API-Key: $API_KEY" "$BASE/policies?perPage=100" | tee /tmp/policies-list.json | head -c 500
curl -s -H "X-API-Key: $API_KEY" "$BASE/tasks?perPage=100" | tee /tmp/tasks-list.json | head -c 500
curl -s -H "X-API-Key: $API_KEY" "$BASE/people?perPage=100" | tee /tmp/people-list.json | head -c 500
curl -s -H "X-API-Key: $API_KEY" "$BASE/vendors?perPage=100" | tee /tmp/vendors-list.json | head -c 500
curl -s -H "X-API-Key: $API_KEY" "$BASE/frameworks" | tee /tmp/frameworks-list.json | head -c 500
```

If any endpoint path 404s, check `apps/api/src/<module>/<module>.controller.ts` on the EC2 box (`cat ~/comp/apps/api/src/controls/controls.controller.ts`) for the actual `@Controller({ path: ..., version: '1' })` prefix and adjust.

- [ ] **Step 2: Copy the captured JSON into this repo as test fixtures**

Copy the six `/tmp/*.json` files into `xamops-service/src/test/resources/compai/` under the names listed in **Files** above (use `scp` from the EC2 box, then move into place with the `Write` tool or `cp`). Pretty-print them first for readability:

```bash
python3 -m json.tool /tmp/controls-list.json > xamops-service/src/test/resources/compai/controls-list.json
# repeat for the other five
```

- [ ] **Step 3: Confirm done**

```bash
ls -la xamops-service/src/test/resources/compai/
```

Expected: six files, all non-empty, all valid JSON (`python3 -m json.tool <file> > /dev/null` exits 0 for each).

---

### Task 13: `CompAiApiClient` — HTTP client with X-API-Key auth

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/service/compai/CompAiApiClient.java`
- Test: `xamops-service/src/test/java/com/xammer/cloud/service/compai/CompAiApiClientTest.java`

**Interfaces:**
- Produces: `CompAiApiClient.isConfigured(): boolean`, `CompAiApiClient.getJson(String path): JsonNode` — every later service method in Task 14/15 calls `getJson(path)` and navigates the returned `JsonNode`.

- [ ] **Step 1: Write the failing test**

```java
package com.xammer.cloud.service.compai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CompAiApiClientTest {

    @Test
    void isConfigured_falseWhenApiKeyBlank() {
        CompAiApiClient client = new CompAiApiClient(new ObjectMapper(), mock(RestTemplate.class));
        client.setApiKey("");
        client.setApiBaseUrl("https://compai-api.example.com/v1");
        assertFalse(client.isConfigured());
    }

    @Test
    void isConfigured_trueWhenApiKeySet() {
        CompAiApiClient client = new CompAiApiClient(new ObjectMapper(), mock(RestTemplate.class));
        client.setApiKey("test-key");
        client.setApiBaseUrl("https://compai-api.example.com/v1");
        assertTrue(client.isConfigured());
    }

    @Test
    void getJson_sendsXApiKeyHeaderAndParsesBody() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        CompAiApiClient client = new CompAiApiClient(new ObjectMapper(), restTemplate);
        client.setApiKey("test-key");
        client.setApiBaseUrl("https://compai-api.example.com/v1");

        ResponseEntity<String> fakeResponse = ResponseEntity.ok("{\"data\":[{\"id\":\"1\"}]}");
        when(restTemplate.exchange(
                eq(java.net.URI.create("https://compai-api.example.com/v1/controls?perPage=100")),
                eq(HttpMethod.GET),
                any(),
                eq(String.class)))
                .thenReturn(fakeResponse);

        JsonNode result = client.getJson("/controls?perPage=100");

        assertEquals("1", result.path("data").get(0).path("id").asText());
        verify(restTemplate).exchange(any(java.net.URI.class), eq(HttpMethod.GET),
                argThat(entity -> {
                    HttpHeaders headers = (HttpHeaders) entity.getHeaders();
                    return "test-key".equals(headers.getFirst("X-API-Key"));
                }), eq(String.class));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f xamops-service/pom.xml test -Dtest=CompAiApiClientTest`
Expected: FAIL — `CompAiApiClient` does not exist yet (compilation error).

- [ ] **Step 3: Write the implementation**

```java
package com.xammer.cloud.service.compai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;

/**
 * Client for the self-hosted comp.ai instance's {@code apps/api} (NestJS), used
 * server-to-server only. Auth: org-scoped API key via the {@code X-API-Key}
 * header (comp.ai's {@code HybridAuthGuard} accepts this for "external
 * customers" — see docs/superpowers/specs/2026-07-24-soc2-compai-demo-design.md).
 * No comp.ai session/login is ever exposed to XamOps users.
 */
@Component
@Slf4j
public class CompAiApiClient {

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Setter @Value("${compai.api-key:}")
    private String apiKey;

    @Setter @Value("${compai.api-base-url:}")
    private String apiBaseUrl;

    public CompAiApiClient(ObjectMapper objectMapper, RestTemplate restTemplate) {
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
    }

    @javax.annotation.PostConstruct
    void logConfigState() {
        log.info("[CompAi] client init — configured={} apiBaseUrl={}", isConfigured(), apiBaseUrl);
        if (!isConfigured()) {
            log.warn("[CompAi] compai.api-key is EMPTY at runtime — the SOC2 Program tabs "
                    + "will return no data. Check COMPAI_API_KEY env var.");
        }
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isEmpty()
                && apiBaseUrl != null && !apiBaseUrl.isEmpty();
    }

    /** GETs {@code apiBaseUrl + path}, parses the body as JSON. path must start with '/'. */
    public JsonNode getJson(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", apiKey);
        headers.set(HttpHeaders.USER_AGENT, "XamOps/1.0");
        ResponseEntity<String> resp = restTemplate.exchange(
                URI.create(apiBaseUrl + path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
        try {
            return objectMapper.readTree(resp.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("comp.ai response was not valid JSON for " + path, e);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f xamops-service/pom.xml test -Dtest=CompAiApiClientTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/service/compai/CompAiApiClient.java \
        xamops-service/src/test/java/com/xammer/cloud/service/compai/CompAiApiClientTest.java
git commit -m "feat(compai): add CompAiApiClient for server-to-server comp.ai calls"
```

---

### Task 14: `CompAiService` + DTOs — Controls and Policies

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/dto/compai/ControlSummaryDto.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/dto/compai/PolicyDto.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/service/compai/CompAiService.java`
- Test: `xamops-service/src/test/java/com/xammer/cloud/service/compai/CompAiServiceTest.java`

**Interfaces:**
- Consumes: `CompAiApiClient.isConfigured()`, `CompAiApiClient.getJson(String)` (Task 13). `RedisCacheService.get(String, TypeReference)`/`.put(String, Object, int)` (existing, see `com.xammer.cloud.service.RedisCacheService`).
- Produces: `CompAiService.getControls(): List<ControlSummaryDto>`, `CompAiService.getPolicies(): List<PolicyDto>` — Task 15 adds sibling methods on the same class; Task 17 (controller) calls all of them.

- [ ] **Step 1: Write the failing test**

```java
package com.xammer.cloud.service.compai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.compai.ControlSummaryDto;
import com.xammer.cloud.dto.compai.PolicyDto;
import com.xammer.cloud.service.RedisCacheService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompAiServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode loadFixture(String name) throws Exception {
        Path path = Path.of("src/test/resources/compai/" + name);
        return mapper.readTree(Files.readString(path));
    }

    @Test
    void getControls_mapsFixtureIntoDtos() throws Exception {
        CompAiApiClient apiClient = mock(CompAiApiClient.class);
        RedisCacheService redis = mock(RedisCacheService.class);
        when(redis.get(anyString(), any())).thenReturn(Optional.empty());
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.getJson("/controls?perPage=100")).thenReturn(loadFixture("controls-list.json"));

        CompAiService service = new CompAiService(apiClient, redis);
        List<ControlSummaryDto> controls = service.getControls();

        assertFalse(controls.isEmpty(), "expected at least one control mapped from the fixture");
        ControlSummaryDto first = controls.get(0);
        assertNotNull(first.getId());
        assertNotNull(first.getName());
    }

    @Test
    void getPolicies_mapsFixtureIntoDtos() throws Exception {
        CompAiApiClient apiClient = mock(CompAiApiClient.class);
        RedisCacheService redis = mock(RedisCacheService.class);
        when(redis.get(anyString(), any())).thenReturn(Optional.empty());
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.getJson("/policies?perPage=100")).thenReturn(loadFixture("policies-list.json"));

        CompAiService service = new CompAiService(apiClient, redis);
        List<PolicyDto> policies = service.getPolicies();

        assertFalse(policies.isEmpty(), "expected at least one policy mapped from the fixture");
        assertNotNull(policies.get(0).getName());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f xamops-service/pom.xml test -Dtest=CompAiServiceTest`
Expected: FAIL — `CompAiService`/DTOs don't exist yet.

- [ ] **Step 3: Write the DTOs**

```java
package com.xammer.cloud.dto.compai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ControlSummaryDto {
    private String id;
    private String name;
    private String category;
    private String status;       // "PASSING" | "IN_PROGRESS" | "NEEDS_ATTENTION"
    private String ownerName;
    private String policyName;
    private int evidenceCount;
}
```

```java
package com.xammer.cloud.dto.compai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PolicyDto {
    private String id;
    private String name;
    private String category;
    private String status;   // "DRAFT" | "IN_REVIEW" | "PUBLISHED"
    private String ownerName;
    private String lastUpdated;
}
```

- [ ] **Step 4: Write `CompAiService`**

Field-path expressions below assume comp.ai's list endpoints return `{"data": [...] }` (typical NestJS/Prisma list-endpoint shape). **If Task 12's captured fixture uses a different top-level key** (e.g. `items`/`results`/a bare array), adjust `.path("data")` below to match — the test written in Step 1 loads the real fixture, so it will fail loudly at exactly the wrong path if this guess is off; fix the path and rerun until green.

```java
package com.xammer.cloud.service.compai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.dto.compai.ControlSummaryDto;
import com.xammer.cloud.dto.compai.PolicyDto;
import com.xammer.cloud.service.RedisCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Read side of the SOC2 Program tabs — proxies comp.ai's self-hosted apps/api
 * (see CompAiApiClient) and normalizes its response shape into XamOps-owned
 * DTOs, so the frontend never depends on comp.ai's raw JSON structure.
 */
@Service
@Slf4j
public class CompAiService {

    private static final int CACHE_MINUTES = 10;

    private final CompAiApiClient apiClient;
    private final RedisCacheService redisCacheService;

    public CompAiService(CompAiApiClient apiClient, RedisCacheService redisCacheService) {
        this.apiClient = apiClient;
        this.redisCacheService = redisCacheService;
    }

    public List<ControlSummaryDto> getControls() {
        if (!apiClient.isConfigured()) return List.of();
        String cacheKey = "compai:controls";
        Optional<List<ControlSummaryDto>> cached =
                redisCacheService.get(cacheKey, new TypeReference<List<ControlSummaryDto>>() {});
        if (cached.isPresent()) return cached.get();

        List<ControlSummaryDto> rows = new ArrayList<>();
        try {
            JsonNode body = apiClient.getJson("/controls?perPage=100");
            for (JsonNode c : body.path("data")) {
                ControlSummaryDto dto = new ControlSummaryDto();
                dto.setId(c.path("id").asText(null));
                dto.setName(c.path("name").asText(null));
                dto.setCategory(c.path("category").asText(c.path("requirementCategory").asText(null)));
                dto.setStatus(mapControlStatus(c.path("status").asText(null)));
                dto.setOwnerName(c.path("owner").path("name").asText(null));
                dto.setPolicyName(c.path("policies").isArray() && c.path("policies").size() > 0
                        ? c.path("policies").get(0).path("name").asText(null) : null);
                dto.setEvidenceCount(c.path("evidence").isArray() ? c.path("evidence").size() : 0);
                rows.add(dto);
            }
        } catch (Exception e) {
            log.warn("[CompAi] controls fetch failed: {}", e.getMessage());
        }
        if (!rows.isEmpty()) redisCacheService.put(cacheKey, rows, CACHE_MINUTES);
        return rows;
    }

    public List<PolicyDto> getPolicies() {
        if (!apiClient.isConfigured()) return List.of();
        String cacheKey = "compai:policies";
        Optional<List<PolicyDto>> cached =
                redisCacheService.get(cacheKey, new TypeReference<List<PolicyDto>>() {});
        if (cached.isPresent()) return cached.get();

        List<PolicyDto> rows = new ArrayList<>();
        try {
            JsonNode body = apiClient.getJson("/policies?perPage=100");
            for (JsonNode p : body.path("data")) {
                PolicyDto dto = new PolicyDto();
                dto.setId(p.path("id").asText(null));
                dto.setName(p.path("name").asText(null));
                dto.setCategory(p.path("category").asText(null));
                dto.setStatus(p.path("status").asText(null));
                dto.setOwnerName(p.path("owner").path("name").asText(null));
                dto.setLastUpdated(p.path("updatedAt").asText(null));
                rows.add(dto);
            }
        } catch (Exception e) {
            log.warn("[CompAi] policies fetch failed: {}", e.getMessage());
        }
        if (!rows.isEmpty()) redisCacheService.put(cacheKey, rows, CACHE_MINUTES);
        return rows;
    }

    private String mapControlStatus(String rawStatus) {
        if (rawStatus == null) return "IN_PROGRESS";
        String s = rawStatus.toUpperCase();
        if (s.contains("PASS") || s.contains("COMPLETE") || s.contains("MET")) return "PASSING";
        if (s.contains("FAIL") || s.contains("ATTENTION") || s.contains("NOT_MET")) return "NEEDS_ATTENTION";
        return "IN_PROGRESS";
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -f xamops-service/pom.xml test -Dtest=CompAiServiceTest`
Expected: PASS. If a fixture field path doesn't match (e.g. `data` should be `items`), adjust the corresponding `.path(...)` call in `CompAiService` and rerun until green — this is the expected one-iteration tuning called out in Step 4.

- [ ] **Step 6: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/dto/compai/ \
        xamops-service/src/main/java/com/xammer/cloud/service/compai/CompAiService.java \
        xamops-service/src/test/java/com/xammer/cloud/service/compai/CompAiServiceTest.java
git commit -m "feat(compai): map controls + policies from comp.ai into XamOps DTOs"
```

---

### Task 15: `CompAiService` — Evidence & Tasks, Personnel, Vendors, Overview

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/dto/compai/TaskDto.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/dto/compai/PersonDto.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/dto/compai/VendorDto.java`
- Create: `xamops-service/src/main/java/com/xammer/cloud/dto/compai/OverviewDto.java`
- Modify: `xamops-service/src/main/java/com/xammer/cloud/service/compai/CompAiService.java` (append methods)
- Test: `xamops-service/src/test/java/com/xammer/cloud/service/compai/CompAiServiceTest.java` (append tests)

**Interfaces:**
- Consumes: same `CompAiApiClient`/`RedisCacheService` as Task 14; also calls the already-written `getControls()` and `getPolicies()` to compute `OverviewDto` counts.
- Produces: `CompAiService.getTasks(): List<TaskDto>`, `.getPersonnel(): List<PersonDto>`, `.getVendors(): List<VendorDto>`, `.getOverview(): OverviewDto` — Task 17's controller exposes all four.

- [ ] **Step 1: Write the failing tests**

Append to `CompAiServiceTest.java`:

```java
    @Test
    void getTasks_mapsFixtureIntoDtos() throws Exception {
        CompAiApiClient apiClient = mock(CompAiApiClient.class);
        RedisCacheService redis = mock(RedisCacheService.class);
        when(redis.get(anyString(), any())).thenReturn(Optional.empty());
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.getJson("/tasks?perPage=100")).thenReturn(loadFixture("tasks-list.json"));

        CompAiService service = new CompAiService(apiClient, redis);
        var tasks = service.getTasks();

        assertFalse(tasks.isEmpty(), "expected at least one task mapped from the fixture");
        assertNotNull(tasks.get(0).getTitle());
    }

    @Test
    void getPersonnel_mapsFixtureIntoDtos() throws Exception {
        CompAiApiClient apiClient = mock(CompAiApiClient.class);
        RedisCacheService redis = mock(RedisCacheService.class);
        when(redis.get(anyString(), any())).thenReturn(Optional.empty());
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.getJson("/people?perPage=100")).thenReturn(loadFixture("people-list.json"));

        CompAiService service = new CompAiService(apiClient, redis);
        var people = service.getPersonnel();

        assertFalse(people.isEmpty(), "expected at least one person mapped from the fixture");
        assertNotNull(people.get(0).getName());
    }

    @Test
    void getVendors_mapsFixtureIntoDtos() throws Exception {
        CompAiApiClient apiClient = mock(CompAiApiClient.class);
        RedisCacheService redis = mock(RedisCacheService.class);
        when(redis.get(anyString(), any())).thenReturn(Optional.empty());
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.getJson("/vendors?perPage=100")).thenReturn(loadFixture("vendors-list.json"));

        CompAiService service = new CompAiService(apiClient, redis);
        var vendors = service.getVendors();

        assertFalse(vendors.isEmpty(), "expected at least one vendor mapped from the fixture");
        assertNotNull(vendors.get(0).getName());
    }

    @Test
    void getOverview_computesCountsFromControlsAndPolicies() throws Exception {
        CompAiApiClient apiClient = mock(CompAiApiClient.class);
        RedisCacheService redis = mock(RedisCacheService.class);
        when(redis.get(anyString(), any())).thenReturn(Optional.empty());
        when(apiClient.isConfigured()).thenReturn(true);
        when(apiClient.getJson("/controls?perPage=100")).thenReturn(loadFixture("controls-list.json"));
        when(apiClient.getJson("/policies?perPage=100")).thenReturn(loadFixture("policies-list.json"));
        when(apiClient.getJson("/tasks?perPage=100")).thenReturn(loadFixture("tasks-list.json"));

        CompAiService service = new CompAiService(apiClient, redis);
        var overview = service.getOverview();

        assertEquals("SOC 2", overview.getFrameworkName());
        assertTrue(overview.getControlsTotal() > 0);
        assertTrue(overview.getReadinessPercent() >= 0 && overview.getReadinessPercent() <= 100);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -f xamops-service/pom.xml test -Dtest=CompAiServiceTest`
Expected: FAIL — new methods/DTOs don't exist yet.

- [ ] **Step 3: Write the remaining DTOs**

```java
package com.xammer.cloud.dto.compai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskDto {
    private String id;
    private String title;
    private String controlName;
    private String ownerName;
    private String dueDate;
    private String status;   // "OPEN" | "IN_PROGRESS" | "COMPLETED" | "OVERDUE"
    private boolean evidenceAttached;
}
```

```java
package com.xammer.cloud.dto.compai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PersonDto {
    private String id;
    private String name;
    private String role;
    private String onboardingStatus;
    private String trainingStatus;
    private String deviceComplianceStatus;
}
```

```java
package com.xammer.cloud.dto.compai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VendorDto {
    private String id;
    private String name;
    private String category;
    private String riskLevel;   // "LOW" | "MEDIUM" | "HIGH"
    private String reviewStatus;
}
```

```java
package com.xammer.cloud.dto.compai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OverviewDto {
    private String frameworkName;
    private int readinessPercent;
    private int controlsTotal;
    private int controlsPassing;
    private int controlsInProgress;
    private int controlsNeedsAttention;
    private int policiesTotal;
    private int policiesPublished;
    private int tasksOpen;
    private int tasksOverdue;
}
```

- [ ] **Step 4: Append the service methods**

Add to `CompAiService.java` (same field-path caveat as Task 14 Step 4 applies — adjust `.path(...)` calls to match the real captured fixtures):

```java
    public List<com.xammer.cloud.dto.compai.TaskDto> getTasks() {
        if (!apiClient.isConfigured()) return List.of();
        String cacheKey = "compai:tasks";
        Optional<List<com.xammer.cloud.dto.compai.TaskDto>> cached =
                redisCacheService.get(cacheKey, new TypeReference<List<com.xammer.cloud.dto.compai.TaskDto>>() {});
        if (cached.isPresent()) return cached.get();

        List<com.xammer.cloud.dto.compai.TaskDto> rows = new ArrayList<>();
        try {
            JsonNode body = apiClient.getJson("/tasks?perPage=100");
            for (JsonNode t : body.path("data")) {
                com.xammer.cloud.dto.compai.TaskDto dto = new com.xammer.cloud.dto.compai.TaskDto();
                dto.setId(t.path("id").asText(null));
                dto.setTitle(t.path("title").asText(null));
                dto.setControlName(t.path("control").path("name").asText(null));
                dto.setOwnerName(t.path("assignee").path("name").asText(null));
                dto.setDueDate(t.path("dueDate").asText(null));
                dto.setStatus(t.path("status").asText("OPEN"));
                dto.setEvidenceAttached(t.path("evidence").isArray() && t.path("evidence").size() > 0);
                rows.add(dto);
            }
        } catch (Exception e) {
            log.warn("[CompAi] tasks fetch failed: {}", e.getMessage());
        }
        if (!rows.isEmpty()) redisCacheService.put(cacheKey, rows, CACHE_MINUTES);
        return rows;
    }

    public List<com.xammer.cloud.dto.compai.PersonDto> getPersonnel() {
        if (!apiClient.isConfigured()) return List.of();
        String cacheKey = "compai:people";
        Optional<List<com.xammer.cloud.dto.compai.PersonDto>> cached =
                redisCacheService.get(cacheKey, new TypeReference<List<com.xammer.cloud.dto.compai.PersonDto>>() {});
        if (cached.isPresent()) return cached.get();

        List<com.xammer.cloud.dto.compai.PersonDto> rows = new ArrayList<>();
        try {
            JsonNode body = apiClient.getJson("/people?perPage=100");
            for (JsonNode p : body.path("data")) {
                com.xammer.cloud.dto.compai.PersonDto dto = new com.xammer.cloud.dto.compai.PersonDto();
                dto.setId(p.path("id").asText(null));
                dto.setName(p.path("name").asText(null));
                dto.setRole(p.path("role").asText(null));
                dto.setOnboardingStatus(p.path("onboardingStatus").asText(null));
                dto.setTrainingStatus(p.path("trainingStatus").asText(null));
                dto.setDeviceComplianceStatus(p.path("deviceComplianceStatus").asText(null));
                rows.add(dto);
            }
        } catch (Exception e) {
            log.warn("[CompAi] people fetch failed: {}", e.getMessage());
        }
        if (!rows.isEmpty()) redisCacheService.put(cacheKey, rows, CACHE_MINUTES);
        return rows;
    }

    public List<com.xammer.cloud.dto.compai.VendorDto> getVendors() {
        if (!apiClient.isConfigured()) return List.of();
        String cacheKey = "compai:vendors";
        Optional<List<com.xammer.cloud.dto.compai.VendorDto>> cached =
                redisCacheService.get(cacheKey, new TypeReference<List<com.xammer.cloud.dto.compai.VendorDto>>() {});
        if (cached.isPresent()) return cached.get();

        List<com.xammer.cloud.dto.compai.VendorDto> rows = new ArrayList<>();
        try {
            JsonNode body = apiClient.getJson("/vendors?perPage=100");
            for (JsonNode v : body.path("data")) {
                com.xammer.cloud.dto.compai.VendorDto dto = new com.xammer.cloud.dto.compai.VendorDto();
                dto.setId(v.path("id").asText(null));
                dto.setName(v.path("name").asText(null));
                dto.setCategory(v.path("category").asText(null));
                dto.setRiskLevel(v.path("riskLevel").asText(null));
                dto.setReviewStatus(v.path("reviewStatus").asText(null));
                rows.add(dto);
            }
        } catch (Exception e) {
            log.warn("[CompAi] vendors fetch failed: {}", e.getMessage());
        }
        if (!rows.isEmpty()) redisCacheService.put(cacheKey, rows, CACHE_MINUTES);
        return rows;
    }

    public com.xammer.cloud.dto.compai.OverviewDto getOverview() {
        List<ControlSummaryDto> controls = getControls();
        List<PolicyDto> policies = getPolicies();
        List<com.xammer.cloud.dto.compai.TaskDto> tasks = getTasks();

        com.xammer.cloud.dto.compai.OverviewDto dto = new com.xammer.cloud.dto.compai.OverviewDto();
        dto.setFrameworkName("SOC 2");
        dto.setControlsTotal(controls.size());
        dto.setControlsPassing((int) controls.stream().filter(c -> "PASSING".equals(c.getStatus())).count());
        dto.setControlsInProgress((int) controls.stream().filter(c -> "IN_PROGRESS".equals(c.getStatus())).count());
        dto.setControlsNeedsAttention((int) controls.stream().filter(c -> "NEEDS_ATTENTION".equals(c.getStatus())).count());
        dto.setReadinessPercent(controls.isEmpty() ? 0 : (dto.getControlsPassing() * 100) / controls.size());
        dto.setPoliciesTotal(policies.size());
        dto.setPoliciesPublished((int) policies.stream().filter(p -> "PUBLISHED".equals(p.getStatus())).count());
        dto.setTasksOpen((int) tasks.stream().filter(t -> !"COMPLETED".equals(t.getStatus())).count());
        dto.setTasksOverdue((int) tasks.stream().filter(t -> "OVERDUE".equals(t.getStatus())).count());
        return dto;
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -f xamops-service/pom.xml test -Dtest=CompAiServiceTest`
Expected: PASS (8 tests total). Adjust field paths against the real fixtures as needed, same as Task 14.

- [ ] **Step 6: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/dto/compai/ \
        xamops-service/src/main/java/com/xammer/cloud/service/compai/CompAiService.java \
        xamops-service/src/test/java/com/xammer/cloud/service/compai/CompAiServiceTest.java
git commit -m "feat(compai): add tasks/personnel/vendors/overview mapping"
```

---

### Task 16: `CompAiController` + application.properties config

**Files:**
- Create: `xamops-service/src/main/java/com/xammer/cloud/controller/compai/CompAiController.java`
- Test: `xamops-service/src/test/java/com/xammer/cloud/controller/compai/CompAiControllerTest.java`
- Modify: `xamops-service/src/main/resources/application.properties`

**Interfaces:**
- Consumes: `CompAiService.getOverview()/.getControls()/.getPolicies()/.getTasks()/.getPersonnel()/.getVendors()` (Tasks 14-15).
- Produces: REST endpoints `GET /api/soc2/overview`, `/controls`, `/policies`, `/tasks`, `/personnel`, `/vendors` — Task 18's frontend `soc2Api.ts` calls these exact paths.

- [ ] **Step 1: Write the failing test**

```java
package com.xammer.cloud.controller.compai;

import com.xammer.cloud.dto.compai.OverviewDto;
import com.xammer.cloud.service.compai.CompAiService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@WebMvcTest(CompAiController.class)
class CompAiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CompAiService compAiService;

    @Test
    @WithMockUser
    void overview_returnsServiceResult() throws Exception {
        OverviewDto dto = new OverviewDto();
        dto.setFrameworkName("SOC 2");
        dto.setControlsTotal(40);
        when(compAiService.getOverview()).thenReturn(dto);

        mockMvc.perform(get("/api/soc2/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frameworkName").value("SOC 2"))
                .andExpect(jsonPath("$.controlsTotal").value(40));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f xamops-service/pom.xml test -Dtest=CompAiControllerTest`
Expected: FAIL — `CompAiController` doesn't exist yet.

- [ ] **Step 3: Write the controller**

```java
package com.xammer.cloud.controller.compai;

import com.xammer.cloud.service.compai.CompAiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/soc2")
@RequiredArgsConstructor
public class CompAiController {

    private final CompAiService compAiService;

    @GetMapping("/overview")
    public ResponseEntity<?> overview() {
        return ResponseEntity.ok(compAiService.getOverview());
    }

    @GetMapping("/controls")
    public ResponseEntity<?> controls() {
        return ResponseEntity.ok(compAiService.getControls());
    }

    @GetMapping("/policies")
    public ResponseEntity<?> policies() {
        return ResponseEntity.ok(compAiService.getPolicies());
    }

    @GetMapping("/tasks")
    public ResponseEntity<?> tasks() {
        return ResponseEntity.ok(compAiService.getTasks());
    }

    @GetMapping("/personnel")
    public ResponseEntity<?> personnel() {
        return ResponseEntity.ok(compAiService.getPersonnel());
    }

    @GetMapping("/vendors")
    public ResponseEntity<?> vendors() {
        return ResponseEntity.ok(compAiService.getVendors());
    }
}
```

- [ ] **Step 4: Add config entries**

Append to `xamops-service/src/main/resources/application.properties`:

```properties
compai.api-key=${COMPAI_API_KEY:}
compai.api-base-url=${COMPAI_API_BASE_URL:https://compai-api.example.com/v1}
```

Set the real `COMPAI_API_KEY` (from Task 6 Step 3) and `COMPAI_API_BASE_URL` (from Task 5) as environment variables on whatever host runs `xamops-service` for the demo — never commit the actual key value.

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -f xamops-service/pom.xml test -Dtest=CompAiControllerTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add xamops-service/src/main/java/com/xammer/cloud/controller/compai/CompAiController.java \
        xamops-service/src/test/java/com/xammer/cloud/controller/compai/CompAiControllerTest.java \
        xamops-service/src/main/resources/application.properties
git commit -m "feat(compai): expose /api/soc2 endpoints backed by comp.ai"
```

---

## Phase 4 — `frontend-app` new SOC2 Program module

### Task 17: `soc2Api.ts` — typed API client functions

**Files:**
- Create: `frontend-app/src/api/soc2Api.ts`
- Test: `frontend-app/src/api/soc2Api.test.ts`

**Interfaces:**
- Consumes: `apiClient` default export from `frontend-app/src/utils/apiClient.ts` (existing — wraps `fetch` with CSRF/tenant headers).
- Produces: `fetchSoc2Overview()`, `fetchSoc2Controls()`, `fetchSoc2Policies()`, `fetchSoc2Tasks()`, `fetchSoc2Personnel()`, `fetchSoc2Vendors()` — Task 19's hook calls all six; their return types (`Soc2Overview`, `Soc2Control`, `Soc2Policy`, `Soc2Task`, `Soc2Person`, `Soc2Vendor`) are re-used by Task 20's components.

- [ ] **Step 1: Write the failing test**

```typescript
import { describe, it, expect, vi, beforeEach } from 'vitest';
import apiClient from '../utils/apiClient';
import { fetchSoc2Overview, fetchSoc2Controls } from './soc2Api';

vi.mock('../utils/apiClient', () => ({
  default: { get: vi.fn() },
}));

describe('soc2Api', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('fetchSoc2Overview calls GET /api/soc2/overview', async () => {
    (apiClient.get as any).mockResolvedValue({ frameworkName: 'SOC 2', controlsTotal: 40 });
    const result = await fetchSoc2Overview();
    expect(apiClient.get).toHaveBeenCalledWith('/api/soc2/overview');
    expect(result.controlsTotal).toBe(40);
  });

  it('fetchSoc2Controls calls GET /api/soc2/controls', async () => {
    (apiClient.get as any).mockResolvedValue([{ id: '1', name: 'MFA enforced', status: 'PASSING' }]);
    const result = await fetchSoc2Controls();
    expect(apiClient.get).toHaveBeenCalledWith('/api/soc2/controls');
    expect(result).toHaveLength(1);
  });
});
```

- [ ] **Step 2: Confirm `apiClient` exposes a `.get()` helper**

```bash
grep -n "export default\|^export const\|\.get =" frontend-app/src/utils/apiClient.ts
```

If `apiClient` only exports a raw `fetchWithAuth`-style function (not a `.get()` method), adjust the test/implementation in Steps 1/3 to call that function directly with a GET method instead — check the actual export shape before writing Step 3's implementation.

- [ ] **Step 3: Run test to verify it fails**

Run: `cd frontend-app && npm run test -- soc2Api.test.ts`
Expected: FAIL — `./soc2Api` module doesn't exist.

- [ ] **Step 4: Write the implementation**

```typescript
import apiClient from '../utils/apiClient';

export interface Soc2Overview {
  frameworkName: string;
  readinessPercent: number;
  controlsTotal: number;
  controlsPassing: number;
  controlsInProgress: number;
  controlsNeedsAttention: number;
  policiesTotal: number;
  policiesPublished: number;
  tasksOpen: number;
  tasksOverdue: number;
}

export interface Soc2Control {
  id: string;
  name: string;
  category: string;
  status: 'PASSING' | 'IN_PROGRESS' | 'NEEDS_ATTENTION';
  ownerName: string;
  policyName: string | null;
  evidenceCount: number;
}

export interface Soc2Policy {
  id: string;
  name: string;
  category: string;
  status: string;
  ownerName: string;
  lastUpdated: string;
}

export interface Soc2Task {
  id: string;
  title: string;
  controlName: string;
  ownerName: string;
  dueDate: string;
  status: 'OPEN' | 'IN_PROGRESS' | 'COMPLETED' | 'OVERDUE';
  evidenceAttached: boolean;
}

export interface Soc2Person {
  id: string;
  name: string;
  role: string;
  onboardingStatus: string;
  trainingStatus: string;
  deviceComplianceStatus: string;
}

export interface Soc2Vendor {
  id: string;
  name: string;
  category: string;
  riskLevel: 'LOW' | 'MEDIUM' | 'HIGH';
  reviewStatus: string;
}

export const fetchSoc2Overview = (): Promise<Soc2Overview> => apiClient.get('/api/soc2/overview');
export const fetchSoc2Controls = (): Promise<Soc2Control[]> => apiClient.get('/api/soc2/controls');
export const fetchSoc2Policies = (): Promise<Soc2Policy[]> => apiClient.get('/api/soc2/policies');
export const fetchSoc2Tasks = (): Promise<Soc2Task[]> => apiClient.get('/api/soc2/tasks');
export const fetchSoc2Personnel = (): Promise<Soc2Person[]> => apiClient.get('/api/soc2/personnel');
export const fetchSoc2Vendors = (): Promise<Soc2Vendor[]> => apiClient.get('/api/soc2/vendors');
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd frontend-app && npm run test -- soc2Api.test.ts`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend-app/src/api/soc2Api.ts frontend-app/src/api/soc2Api.test.ts
git commit -m "feat(soc2): add typed API client for /api/soc2 endpoints"
```

---

### Task 18: `useSoc2ProgramData` hook

**Files:**
- Create: `frontend-app/src/hooks/useSoc2ProgramData.ts`
- Test: `frontend-app/src/hooks/useSoc2ProgramData.test.ts`

**Interfaces:**
- Consumes: all six `fetchSoc2*` functions from Task 17's `soc2Api.ts`.
- Produces: `useSoc2ProgramData()` returning `{ overview, controls, policies, tasks, personnel, vendors, isLoading, error, refetch }` — Task 19's page consumes this shape directly.

- [ ] **Step 1: Write the failing test**

```typescript
import { describe, it, expect, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { useSoc2ProgramData } from './useSoc2ProgramData';
import * as soc2Api from '../api/soc2Api';

vi.mock('../api/soc2Api');

describe('useSoc2ProgramData', () => {
  it('loads all six data sets and flips isLoading to false', async () => {
    vi.mocked(soc2Api.fetchSoc2Overview).mockResolvedValue({
      frameworkName: 'SOC 2', readinessPercent: 80, controlsTotal: 40, controlsPassing: 32,
      controlsInProgress: 5, controlsNeedsAttention: 3, policiesTotal: 10, policiesPublished: 8,
      tasksOpen: 3, tasksOverdue: 1,
    });
    vi.mocked(soc2Api.fetchSoc2Controls).mockResolvedValue([]);
    vi.mocked(soc2Api.fetchSoc2Policies).mockResolvedValue([]);
    vi.mocked(soc2Api.fetchSoc2Tasks).mockResolvedValue([]);
    vi.mocked(soc2Api.fetchSoc2Personnel).mockResolvedValue([]);
    vi.mocked(soc2Api.fetchSoc2Vendors).mockResolvedValue([]);

    const { result } = renderHook(() => useSoc2ProgramData());

    expect(result.current.isLoading).toBe(true);
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.overview?.controlsTotal).toBe(40);
    expect(result.current.error).toBeNull();
  });

  it('sets error when a fetch rejects', async () => {
    vi.mocked(soc2Api.fetchSoc2Overview).mockRejectedValue(new Error('network down'));
    vi.mocked(soc2Api.fetchSoc2Controls).mockResolvedValue([]);
    vi.mocked(soc2Api.fetchSoc2Policies).mockResolvedValue([]);
    vi.mocked(soc2Api.fetchSoc2Tasks).mockResolvedValue([]);
    vi.mocked(soc2Api.fetchSoc2Personnel).mockResolvedValue([]);
    vi.mocked(soc2Api.fetchSoc2Vendors).mockResolvedValue([]);

    const { result } = renderHook(() => useSoc2ProgramData());

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.error).not.toBeNull();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend-app && npm run test -- useSoc2ProgramData.test.ts`
Expected: FAIL — hook module doesn't exist.

- [ ] **Step 3: Write the implementation**

```typescript
import { useState, useEffect, useCallback } from 'react';
import {
  fetchSoc2Overview, fetchSoc2Controls, fetchSoc2Policies,
  fetchSoc2Tasks, fetchSoc2Personnel, fetchSoc2Vendors,
  Soc2Overview, Soc2Control, Soc2Policy, Soc2Task, Soc2Person, Soc2Vendor,
} from '../api/soc2Api';

export const useSoc2ProgramData = () => {
  const [overview, setOverview] = useState<Soc2Overview | null>(null);
  const [controls, setControls] = useState<Soc2Control[]>([]);
  const [policies, setPolicies] = useState<Soc2Policy[]>([]);
  const [tasks, setTasks] = useState<Soc2Task[]>([]);
  const [personnel, setPersonnel] = useState<Soc2Person[]>([]);
  const [vendors, setVendors] = useState<Soc2Vendor[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);

  const fetchData = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const [ov, ctrl, pol, tsk, ppl, vnd] = await Promise.all([
        fetchSoc2Overview(),
        fetchSoc2Controls(),
        fetchSoc2Policies(),
        fetchSoc2Tasks(),
        fetchSoc2Personnel(),
        fetchSoc2Vendors(),
      ]);
      setOverview(ov);
      setControls(ctrl);
      setPolicies(pol);
      setTasks(tsk);
      setPersonnel(ppl);
      setVendors(vnd);
    } catch (e) {
      setError(e instanceof Error ? e : new Error('Failed to load SOC2 program data'));
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  return { overview, controls, policies, tasks, personnel, vendors, isLoading, error, refetch: fetchData };
};
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend-app && npm run test -- useSoc2ProgramData.test.ts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend-app/src/hooks/useSoc2ProgramData.ts frontend-app/src/hooks/useSoc2ProgramData.test.ts
git commit -m "feat(soc2): add useSoc2ProgramData hook"
```

---

### Task 19: `Soc2ProgramPage.tsx` — tab shell + Overview tab

**Files:**
- Create: `frontend-app/src/pages/Soc2ProgramPage.tsx`
- Test: `frontend-app/src/pages/Soc2ProgramPage.test.tsx`

**Interfaces:**
- Consumes: `useSoc2ProgramData()` (Task 18). `useTopbar()` from `frontend-app/src/contexts/TopbarContext.tsx` (existing — `setTitle`, `setActions`, `clearActions`).
- Produces: default export `Soc2ProgramPage` — Task 21 lazy-imports this exact component and mounts it at `/soc2-program/:tab`. Reads `tab` from `useParams<{ tab: string }>()`; valid values `overview | controls | policies | evidence-tasks | personnel | vendors`.

- [ ] **Step 1: Write the failing test**

```typescript
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import Soc2ProgramPage from './Soc2ProgramPage';
import { useSoc2ProgramData } from '../hooks/useSoc2ProgramData';

vi.mock('../hooks/useSoc2ProgramData');
vi.mock('../contexts/TopbarContext', () => ({
  useTopbar: () => ({ setTitle: vi.fn(), setActions: vi.fn(), clearActions: vi.fn() }),
}));

describe('Soc2ProgramPage', () => {
  it('renders the Overview tab readiness percent when data loads', () => {
    vi.mocked(useSoc2ProgramData).mockReturnValue({
      overview: {
        frameworkName: 'SOC 2', readinessPercent: 80, controlsTotal: 40, controlsPassing: 32,
        controlsInProgress: 5, controlsNeedsAttention: 3, policiesTotal: 10, policiesPublished: 8,
        tasksOpen: 3, tasksOverdue: 1,
      },
      controls: [], policies: [], tasks: [], personnel: [], vendors: [],
      isLoading: false, error: null, refetch: vi.fn(),
    } as any);

    render(
      <MemoryRouter initialEntries={['/soc2-program/overview']}>
        <Routes>
          <Route path="/soc2-program/:tab" element={<Soc2ProgramPage />} />
        </Routes>
      </MemoryRouter>
    );

    expect(screen.getByText(/80%/)).toBeInTheDocument();
    expect(screen.getByText(/SOC 2/)).toBeInTheDocument();
  });

  it('shows a loading state while data is fetching', () => {
    vi.mocked(useSoc2ProgramData).mockReturnValue({
      overview: null, controls: [], policies: [], tasks: [], personnel: [], vendors: [],
      isLoading: true, error: null, refetch: vi.fn(),
    } as any);

    render(
      <MemoryRouter initialEntries={['/soc2-program/overview']}>
        <Routes>
          <Route path="/soc2-program/:tab" element={<Soc2ProgramPage />} />
        </Routes>
      </MemoryRouter>
    );

    expect(screen.getByText(/loading/i)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend-app && npm run test -- Soc2ProgramPage.test.tsx`
Expected: FAIL — page module doesn't exist.

- [ ] **Step 3: Write the implementation**

```tsx
import React, { useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useTopbar } from '../contexts/TopbarContext';
import { useSoc2ProgramData } from '../hooks/useSoc2ProgramData';

const TABS = [
  { id: 'overview', label: 'Overview' },
  { id: 'controls', label: 'Controls' },
  { id: 'policies', label: 'Policies' },
  { id: 'evidence-tasks', label: 'Evidence & Tasks' },
  { id: 'personnel', label: 'Personnel' },
  { id: 'vendors', label: 'Vendors' },
] as const;

const TRUST_CENTER_URL = import.meta.env.VITE_SOC2_TRUST_CENTER_URL || 'https://trust.example.com';

const StatCard: React.FC<{ label: string; value: string | number }> = ({ label, value }) => (
  <div className="border border-gray-200 rounded-lg p-4 bg-white">
    <div className="text-sm text-gray-500">{label}</div>
    <div className="text-2xl font-semibold text-gray-900">{value}</div>
  </div>
);

const Soc2ProgramPage: React.FC = () => {
  const { tab = 'overview' } = useParams<{ tab: string }>();
  const navigate = useNavigate();
  const { setTitle, setActions, clearActions } = useTopbar();
  const { overview, controls, policies, tasks, personnel, vendors, isLoading, error } = useSoc2ProgramData();

  useEffect(() => {
    setTitle('SOC 2 Program');
    setActions(
      <a href={TRUST_CENTER_URL} target="_blank" rel="noopener noreferrer"
         className="text-sm font-medium text-gray-900 border border-gray-300 rounded-md px-3 py-1.5 hover:bg-gray-50">
        View Trust Center ↗
      </a>
    );
    return () => clearActions();
  }, [setTitle, setActions, clearActions]);

  if (isLoading) {
    return <div className="p-6 text-gray-500">Loading SOC 2 program data...</div>;
  }
  if (error) {
    return <div className="p-6 text-red-600">Failed to load SOC 2 program data: {error.message}</div>;
  }

  return (
    <div className="p-6">
      <div className="flex gap-2 border-b border-gray-200 mb-6">
        {TABS.map((t) => (
          <button
            key={t.id}
            onClick={() => navigate(`/soc2-program/${t.id}`)}
            className={`px-4 py-2 text-sm font-medium border-b-2 ${
              tab === t.id ? 'border-gray-900 text-gray-900' : 'border-transparent text-gray-500 hover:text-gray-700'
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      {tab === 'overview' && overview && (
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <StatCard label="Readiness" value={`${overview.readinessPercent}%`} />
          <StatCard label="Controls passing" value={`${overview.controlsPassing}/${overview.controlsTotal}`} />
          <StatCard label="Policies published" value={`${overview.policiesPublished}/${overview.policiesTotal}`} />
          <StatCard label="Open tasks" value={overview.tasksOpen} />
          <StatCard label="Framework" value={overview.frameworkName} />
          <StatCard label="Needs attention" value={overview.controlsNeedsAttention} />
          <StatCard label="Overdue tasks" value={overview.tasksOverdue} />
        </div>
      )}
      {tab === 'controls' && <div data-testid="controls-count">{controls.length} controls loaded</div>}
      {tab === 'policies' && <div data-testid="policies-count">{policies.length} policies loaded</div>}
      {tab === 'evidence-tasks' && <div data-testid="tasks-count">{tasks.length} tasks loaded</div>}
      {tab === 'personnel' && <div data-testid="personnel-count">{personnel.length} people loaded</div>}
      {tab === 'vendors' && <div data-testid="vendors-count">{vendors.length} vendors loaded</div>}
    </div>
  );
};

export default Soc2ProgramPage;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend-app && npm run test -- Soc2ProgramPage.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend-app/src/pages/Soc2ProgramPage.tsx frontend-app/src/pages/Soc2ProgramPage.test.tsx
git commit -m "feat(soc2): add Soc2ProgramPage with tab shell and Overview tab"
```

---

### Task 20: Controls, Policies, Evidence & Tasks, Personnel, Vendors tab content

**Files:**
- Modify: `frontend-app/src/pages/Soc2ProgramPage.tsx` (replace the five placeholder `data-testid` divs with real tables)
- Modify: `frontend-app/src/pages/Soc2ProgramPage.test.tsx` (extend assertions)

**Interfaces:**
- Consumes: `Soc2Control[]`, `Soc2Policy[]`, `Soc2Task[]`, `Soc2Person[]`, `Soc2Vendor[]` types from Task 17.
- Produces: same default export, now rendering full tab bodies — no new interface for later tasks.

- [ ] **Step 1: Extend the test**

Append to `Soc2ProgramPage.test.tsx` (reusing the same mock data setup pattern as Task 19's first test, with non-empty arrays):

```typescript
  it('renders a row per control on the Controls tab', () => {
    vi.mocked(useSoc2ProgramData).mockReturnValue({
      overview: null,
      controls: [
        { id: '1', name: 'MFA enforced for privileged access', category: 'CC6', status: 'PASSING', ownerName: 'Tom Reyes', policyName: 'Access Control Policy', evidenceCount: 2 },
        { id: '2', name: 'User access deprovisioning', category: 'CC6', status: 'NEEDS_ATTENTION', ownerName: 'Tom Reyes', policyName: null, evidenceCount: 0 },
      ],
      policies: [], tasks: [], personnel: [], vendors: [],
      isLoading: false, error: null, refetch: vi.fn(),
    } as any);

    render(
      <MemoryRouter initialEntries={['/soc2-program/controls']}>
        <Routes>
          <Route path="/soc2-program/:tab" element={<Soc2ProgramPage />} />
        </Routes>
      </MemoryRouter>
    );

    expect(screen.getByText('MFA enforced for privileged access')).toBeInTheDocument();
    expect(screen.getByText('Needs Attention')).toBeInTheDocument();
  });
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend-app && npm run test -- Soc2ProgramPage.test.tsx`
Expected: FAIL — Controls tab currently renders a count, not a table with row text.

- [ ] **Step 3: Replace the five placeholder tab bodies**

In `Soc2ProgramPage.tsx`, replace the five `data-testid` lines from Task 19 Step 3 with:

```tsx
      {tab === 'controls' && (
        <table className="w-full text-sm border border-gray-200 rounded-lg overflow-hidden">
          <thead className="bg-gray-50 text-left text-gray-500">
            <tr>
              <th className="p-3">Control</th>
              <th className="p-3">Category</th>
              <th className="p-3">Status</th>
              <th className="p-3">Owner</th>
              <th className="p-3">Policy</th>
              <th className="p-3">Evidence</th>
            </tr>
          </thead>
          <tbody>
            {controls.map((c) => (
              <tr key={c.id} className="border-t border-gray-100">
                <td className="p-3 text-gray-900">{c.name}</td>
                <td className="p-3 text-gray-500">{c.category}</td>
                <td className="p-3">
                  <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${
                    c.status === 'PASSING' ? 'bg-gray-100 text-gray-900' :
                    c.status === 'NEEDS_ATTENTION' ? 'bg-gray-900 text-white' : 'bg-gray-50 text-gray-600 border border-gray-200'
                  }`}>
                    {c.status === 'PASSING' ? 'Passing' : c.status === 'NEEDS_ATTENTION' ? 'Needs Attention' : 'In Progress'}
                  </span>
                </td>
                <td className="p-3 text-gray-500">{c.ownerName}</td>
                <td className="p-3 text-gray-500">{c.policyName || '—'}</td>
                <td className="p-3 text-gray-500">{c.evidenceCount}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {tab === 'policies' && (
        <table className="w-full text-sm border border-gray-200 rounded-lg overflow-hidden">
          <thead className="bg-gray-50 text-left text-gray-500">
            <tr><th className="p-3">Policy</th><th className="p-3">Status</th><th className="p-3">Owner</th><th className="p-3">Last updated</th></tr>
          </thead>
          <tbody>
            {policies.map((p) => (
              <tr key={p.id} className="border-t border-gray-100">
                <td className="p-3 text-gray-900">{p.name}</td>
                <td className="p-3 text-gray-500">{p.status}</td>
                <td className="p-3 text-gray-500">{p.ownerName}</td>
                <td className="p-3 text-gray-500">{p.lastUpdated}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {tab === 'evidence-tasks' && (
        <table className="w-full text-sm border border-gray-200 rounded-lg overflow-hidden">
          <thead className="bg-gray-50 text-left text-gray-500">
            <tr><th className="p-3">Task</th><th className="p-3">Control</th><th className="p-3">Owner</th><th className="p-3">Due</th><th className="p-3">Status</th></tr>
          </thead>
          <tbody>
            {tasks.map((t) => (
              <tr key={t.id} className="border-t border-gray-100">
                <td className="p-3 text-gray-900">{t.title}</td>
                <td className="p-3 text-gray-500">{t.controlName}</td>
                <td className="p-3 text-gray-500">{t.ownerName}</td>
                <td className="p-3 text-gray-500">{t.dueDate}</td>
                <td className="p-3 text-gray-500">{t.status}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {tab === 'personnel' && (
        <table className="w-full text-sm border border-gray-200 rounded-lg overflow-hidden">
          <thead className="bg-gray-50 text-left text-gray-500">
            <tr><th className="p-3">Name</th><th className="p-3">Role</th><th className="p-3">Onboarding</th><th className="p-3">Training</th><th className="p-3">Device</th></tr>
          </thead>
          <tbody>
            {personnel.map((p) => (
              <tr key={p.id} className="border-t border-gray-100">
                <td className="p-3 text-gray-900">{p.name}</td>
                <td className="p-3 text-gray-500">{p.role}</td>
                <td className="p-3 text-gray-500">{p.onboardingStatus}</td>
                <td className="p-3 text-gray-500">{p.trainingStatus}</td>
                <td className="p-3 text-gray-500">{p.deviceComplianceStatus}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {tab === 'vendors' && (
        <table className="w-full text-sm border border-gray-200 rounded-lg overflow-hidden">
          <thead className="bg-gray-50 text-left text-gray-500">
            <tr><th className="p-3">Vendor</th><th className="p-3">Category</th><th className="p-3">Risk</th><th className="p-3">Review status</th></tr>
          </thead>
          <tbody>
            {vendors.map((v) => (
              <tr key={v.id} className="border-t border-gray-100">
                <td className="p-3 text-gray-900">{v.name}</td>
                <td className="p-3 text-gray-500">{v.category}</td>
                <td className="p-3 text-gray-500">{v.riskLevel}</td>
                <td className="p-3 text-gray-500">{v.reviewStatus}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend-app && npm run test -- Soc2ProgramPage.test.tsx`
Expected: PASS (all tests, including Task 19's).

- [ ] **Step 5: Commit**

```bash
git add frontend-app/src/pages/Soc2ProgramPage.tsx frontend-app/src/pages/Soc2ProgramPage.test.tsx
git commit -m "feat(soc2): render Controls/Policies/Evidence & Tasks/Personnel/Vendors tables"
```

---

### Task 21: Route, Sidebar nav item, and env var wiring

**Files:**
- Modify: `frontend-app/src/App.tsx`
- Modify: `frontend-app/src/components/Sidebar/Sidebar.tsx`
- Modify: `frontend-app/.env.example` (or wherever `VITE_*` vars are documented — check for an existing `.env.example`)

**Interfaces:**
- Consumes: `Soc2ProgramPage` default export (Task 19/20).
- Produces: reachable route `/soc2-program/:tab`, visible Sidebar entry — nothing downstream depends on this task; it's the integration point.

- [ ] **Step 1: Add the lazy import and routes**

In `frontend-app/src/App.tsx`, find the existing lazy-import block (near `const ComplianceOpsPage = lazy(() => import('./pages/ComplianceOpsPage'));` at line 138) and add directly below it:

```typescript
const Soc2ProgramPage = lazy(() => import('./pages/Soc2ProgramPage'));
```

Find the existing route block (near `<Route path="/complianceops" element={<ComplianceOpsPage />} />` at line 596) and add:

```tsx
<Route path="/soc2-program" element={<Navigate to="/soc2-program/overview" replace />} />
<Route path="/soc2-program/:tab" element={<Soc2ProgramPage />} />
```

- [ ] **Step 2: Add the Sidebar nav item**

In `frontend-app/src/components/Sidebar/Sidebar.tsx`, find the ComplianceOps `NavItem` (around line 386: `{isXamTabAllowed('secops-compliance') && <NavItem href="/complianceops" ... label="ComplianceOps" .../>}`) and add directly below it:

```tsx
<NavItem href="/soc2-program/overview" icon="fas fa-clipboard-check" label="SOC2 Program" isActive={isActive('/soc2-program')} />
```

This entry is unconditional (no `isXamTabAllowed` gate) since it's a demo-only feature, not a tenant-configurable one — confirm this matches how other non-gated `NavItem`s in the same file look (e.g. search the file for a `NavItem` without an `isXamTabAllowed` wrapper) before finalizing; if every single entry in this file is gated, wrap it in `isXamTabAllowed('secops-compliance')` for consistency instead of leaving it ungated.

- [ ] **Step 3: Add the Trust Center URL env var**

Check whether `frontend-app/.env.example` exists:

```bash
ls frontend-app/.env.example 2>/dev/null && cat frontend-app/.env.example
```

If it exists, append:

```
VITE_SOC2_TRUST_CENTER_URL=https://trust.<yourdomain>
```

If it doesn't exist, create `frontend-app/.env.local` (gitignored) with the same line, and note in the commit message that this var must be set per-environment.

- [ ] **Step 4: Manually verify in the browser**

```bash
cd frontend-app && npm run dev
```

Navigate to `http://localhost:5173/soc2-program/overview` (adjust port if different), confirm: the SOC 2 Program page loads, the Topbar shows "SOC 2 Program", clicking each of the 6 tabs switches content, "View Trust Center ↗" opens the comp.ai portal URL in a new tab, and the Sidebar shows the new nav item.

- [ ] **Step 5: Run the full frontend test suite**

Run: `cd frontend-app && npm run test`
Expected: all tests pass, including the new `soc2Api.test.ts`, `useSoc2ProgramData.test.ts`, `Soc2ProgramPage.test.tsx`.

- [ ] **Step 6: Commit**

```bash
git add frontend-app/src/App.tsx frontend-app/src/components/Sidebar/Sidebar.tsx
git commit -m "feat(soc2): register /soc2-program route and Sidebar nav item"
```

---

## Post-implementation checklist (not a task — a pre-demo walkthrough)

Before Monday, walk the exact path the customer will see:
1. Log into XamOps, click "SOC2 Program" in the sidebar.
2. Overview tab: readiness % and counts look plausible (not 100%, not 0%).
3. Controls tab: click through a few rows, confirm owner/policy/evidence columns are populated, not blank.
4. Policies tab: confirm policy names/status make sense next to the controls that reference them.
5. Evidence & Tasks tab: confirm a couple of open/overdue tasks show (not everything "Completed").
6. Personnel tab: confirm the Jordan Lee (new hire, pending) row is visible — the "we catch this stuff" narrative beat.
7. Vendors tab: confirm the overdue-review vendor is visible.
8. Click "View Trust Center ↗" — confirm it opens the branded Re Digital public page in a new tab with no login required.
