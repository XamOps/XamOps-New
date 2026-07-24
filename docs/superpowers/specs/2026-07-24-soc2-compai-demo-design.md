# SOC 2 Demo via comp.ai — Design

**Date:** 2026-07-24
**Deadline:** Monday demo (2026-07-27)
**Feature area:** New nav area in `frontend-app`, backed by a self-hosted comp.ai instance

---

## Problem

A customer demo on Monday needs to show that SOC 2 compliance can be driven through the XamOps UI — not just the technical cloud-control scoring XamOps already has (ComplianceOps / XamSecure), but the full GRC workflow: policies, evidence/tasks, personnel, vendor risk, and a trust center. [trycomp.ai](https://www.trycomp.ai) / [github.com/trycompai/comp](https://github.com/trycompai/comp) is an open-source SOC 2 automation platform (NestJS API + Next.js app/portal + Postgres/Prisma) that already implements this domain model.

## Scope & non-goals

This is a **static, seeded demo**, not a production integration:

- One framework: **SOC 2**, one fictional tenant company: **Re Digital**, framed as mid-audit (mixed control statuses, not a suspicious 100%).
- ~40 controls across the five SOC 2 trust service categories, each with an owner, a linked policy, and at least one attached evidence artifact.
- ~8–10 policies with real prose (Access Control, Incident Response, Data Retention, Vendor Management, Change Management, etc.), authored via comp.ai's own AI policy generation or drafted directly.
- 5–8 employees with mixed onboarding/training/device-compliance status.
- 4–6 vendors with risk scores/categories.
- **Explicitly not built:** live integrations, Trigger.dev/Resend-driven workflows, device agent, real evidence auto-collection. All content is seeded once ahead of time. Any interaction that appears "live" (e.g. marking a task reviewed) works because it's a real database behind a real app — not something built specially for the demo.

## Architecture

**EC2 (single instance, Docker Compose):**
- Postgres (containerized; no RDS/SSL — demo-grade only)
- comp.ai `apps/api` (NestJS) — serves all data XamOps will read
- comp.ai `apps/app` (Next.js) — used only by us to author/seed content through comp.ai's real UI (control library, AI policy generation, evidence, people, vendors). Never shown to the customer.
- comp.ai `apps/portal` (Next.js) — the public Trust Center, on its own subdomain, branded for Re Digital.

**`xamops-service` (new proxy layer):**
- Holds the comp.ai org's API key server-side (comp.ai has a built-in `api-key.guard`/`api-key.service` for exactly this) and forwards read calls to `apps/api`: frameworks, controls, policies, evidence, tasks, people, vendors.
- Translates comp.ai's JSON into XamOps DTOs consumed by the new frontend module.
- No comp.ai login/session is ever exposed to XamOps users — auth stays entirely on the XamOps side (existing session), comp.ai is called server-to-server only.

**`frontend-app` (new top-level nav item):**
- Sub-tabs: **Overview, Controls, Policies, Evidence & Tasks, Personnel, Vendors**, built with XamOps' existing components/design system (black/white/grey, existing icon set) — no comp.ai UI is embedded.
- **Trust Center:** not a native tab — a "View Trust Center ↗" link that opens comp.ai's own `apps/portal` in a new tab. This matches how real trust centers work (Vanta/Drata/comp.ai itself all use a separate, standalone public page) — embedding it natively would look less authentic, not more.

## Content plan

- Fictional company: **Re Digital**.
- Controls: mixed status (mostly passing, a handful "in progress"/"needs attention") to read as genuinely mid-audit.
- Policies: real prose, not lorem ipsum, generated via comp.ai's AI policy generation (needs an LLM key on the EC2 box) or drafted directly if that's faster.
- Personnel/vendors: mixed compliance status, not all green.

## Deployment responsibilities

- **User:** launch EC2 instance, install Docker, clone comp.ai, run `docker compose` (Postgres + `apps/api` + `apps/app` + `apps/portal`), create the Re Digital org, generate the API key, hand over API key/base URL, author/seed content through comp.ai's UI alongside Claude.
- **Claude:** exact copy-paste command blocks for every EC2/Docker step (per the project's established UAT/prod convention — one complete block per step, since the user is the terminal on remote hosts), the `xamops-service` proxy layer, and the new native frontend module (Overview / Controls / Policies / Evidence & Tasks / Personnel / Vendors + Trust Center link-out).

## Known risks

- **Timeline:** full stack (3 comp.ai apps + Postgres + new backend proxy + new frontend module + ~40 controls of curated content) in a few days is tight. If time runs short, the fallback order is: Controls + Policies + Overview first (highest "wow", most reused from comp.ai's own data model), Evidence & Tasks second, Personnel/Vendors last, Trust Center can ship with a smaller control set if needed.
- **Content authoring is the real bottleneck**, not code — the ~40 controls/8-10 policies need domain judgment to look authentically mid-audit, not code changes.
- **AI policy generation** on comp.ai's side depends on an LLM API key being configured on the EC2 box; if unavailable, policies get drafted directly instead.
