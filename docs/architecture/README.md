# XamOps — Architecture Documentation

This is the architecture documentation set for the **XamOps** platform: a multi-cloud
(AWS / GCP / Azure) FinOps, cloud-security-posture, inventory, Kubernetes-optimization and
agentic-SRE SaaS. Every claim in these documents is grounded in files that were read in the
repository; file paths are cited inline next to the claims they support. Anything that could
not be confirmed from a source file is marked explicitly as **(unverified)** rather than
invented.

> **Scope note.** These docs describe the system *as the code expresses it*. They do not
> reproduce secret values found in source (e.g. credentials embedded in `Jenkinsfile` or
> `application*.properties`); such findings are flagged, not copied. See
> [06-cross-cutting-concerns.md](06-cross-cutting-concerns.md).

---

## Table of contents

| # | Document | What it covers |
|---|---|---|
| — | [README.md](README.md) | This index: the doc map and how to read it. |
| 01 | [01-system-overview.md](01-system-overview.md) | What XamOps is, the full service inventory table, tech stack, and the top-level system-context + container diagrams. |
| 02 | [02-service-architecture.md](02-service-architecture.md) | Per-service deep dive (one section per service): responsibility, key packages, controllers grouped by domain, external integrations, inter-service dependencies, port. |
| 03 | [03-application-flow.md](03-application-flow.md) | **The centerpiece** — end-to-end request flows as Mermaid sequence diagrams (login/auth, onboarding, dashboard/cost read, scheduled ingestion, websocket/realtime, impersonation, agent ingest, forecasting, autoscaler policy loop, etc.), each backed by cited files. |
| 04 | [04-data-architecture.md](04-data-architecture.md) | Databases, the multi-tenant routing model, JPA entity catalog, Flyway + root SQL migrations, caching, and the Kafka SRE stream. |
| 05 | [05-deployment-infrastructure.md](05-deployment-infrastructure.md) | Runtime topology, Dockerfiles, docker-compose, the Jenkins CI/CD pipeline, EKS manifests, CloudFormation onboarding roles, and environments. |
| 06 | [06-cross-cutting-concerns.md](06-cross-cutting-concerns.md) | Security/auth model, multi-tenant isolation, Eureka discovery, observability/logging, error handling, the `permissions-common` shared library, and secrets-in-source risks. |

## How to read this set

1. Start with **[01-system-overview.md](01-system-overview.md)** for the shape of the whole
   platform and the service inventory. Read the diagrams there first — they give you the
   mental model everything else hangs off.
2. Read **[02-service-architecture.md](02-service-architecture.md)** to understand each
   service in isolation. `xamops-service` is the core and gets the most depth; the other
   twelve services each get their own section.
3. Read **[03-application-flow.md](03-application-flow.md)** to see how the services actually
   talk to each other at runtime. This is where the system "moves."
4. Dip into **[04](04-data-architecture.md)**, **[05](05-deployment-infrastructure.md)** and
   **[06](06-cross-cutting-concerns.md)** for data, deployment and cross-cutting detail as
   needed.

## Conventions used in these docs

- **Mermaid** is used for all diagrams: `flowchart`/`graph` for topology, `sequenceDiagram`
  for flows, `erDiagram` for data.
- **Inline citations** look like `` `path/to/File.java` `` (optionally with a `:line`), and
  point at the file that backs the statement.
- **(unverified)** marks a statement inferred but not confirmed from a source file.
- Counts quoted in the docs (e.g. controller count, Flyway script count, entity count) were
  measured against the repository at documentation time: **134 controller classes**, **90
  Flyway migration scripts**, **112 JPA entity classes** under
  `xamops-service/src/main/java/com/xammer/cloud/domain/`.
