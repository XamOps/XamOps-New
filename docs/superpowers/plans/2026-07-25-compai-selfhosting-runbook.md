# comp.ai Self-Hosting Runbook (SOC2 Demo — EC2)

Reproducibility record for the self-hosted comp.ai stack behind the Monday SOC2 demo.
comp.ai's Docker self-hosting is officially unfinished ("Docker steps coming soon" in their README);
this documents every fix applied to get it running so it's not re-derived under pressure.

**Host:** AWS EC2 `18.61.66.186`, Ubuntu, `~/comp` (clone of trycompai/comp), 100GB disk, 8GB swap.
**Org:** "Re Digital" `org_6a644b8b15a1d9925229c45e`, SOC 2 framework enrolled. API key `comp_...` (held by user).
**Security group inbound:** 22, 3000, 3002, 3333 (My IP).

## Stack

- `docker-compose.override.yml` adds: `db` (postgres:16, container_name comp-db, SSL on via self-signed cert in `./pgcerts`) and `api` (built from `apps/api/Dockerfile.multistage`, target production, container_name comp-api, port 3333).
- Root `docker-compose.yml` provides migrator/seeder/app/portal.
- Separate env files: `packages/db/.env`, `apps/app/.env`, `apps/portal/.env`, `apps/api/.env`.

## Dockerfile patches (root `Dockerfile`)

1. **deps stage**: added `COPY packages/{auth,billing,company,db}/package.json` lines (were missing → bun workspace resolution failed).
2. **app-builder + portal-builder**: replaced the partial db prisma-prep with `RUN cd packages/db && bun run build`, then `RUN cd packages/{auth,billing,company} && bun run build` (these packages need their own tsc/tsup build before Next can import them).
3. **portal-builder**: replaced `cp .../dist/schema.prisma apps/portal/prisma/schema.prisma` with copying all `packages/db/prisma/schema/*.prisma` into `apps/portal/prisma/schema/` + rewriting portal's own `schema.prisma` header (Prisma dir-mode ignored the orphaned sibling file).
4. **migrator stage** `package.json`: `prisma`/`@prisma/client` → `7.6.0`, added `@prisma/adapter-pg@7.6.0`, `@trycompai/db@^2.3.1`, `zod@^4.3.6`; `bun install` → `bun install --ignore-scripts` (Prisma 7 preinstall Node-version check fails under bun).

## docker-compose.yml patch

- `app` and `portal` services: added `NEXT_PUBLIC_API_URL: ${NEXT_PUBLIC_API_URL}` build-arg (NEXT_PUBLIC_* are baked at build time; was defaulting to localhost:3333 in the browser bundle).
- Build with `export BETTER_AUTH_URL=http://18.61.66.186:3000 BETTER_AUTH_URL_PORTAL=http://18.61.66.186:3002 NEXT_PUBLIC_API_URL=http://18.61.66.186:3333` then `docker compose build`.
- Build `app` and `portal` one at a time (concurrent builds OOM an 8GB box; each Next build wants up to 6GB). `nest build` (api) takes ~11 min silent — not stuck.

## Required env (beyond the .env.example values)

All URLs use `http://18.61.66.186:{3000|3002|3333}`.

- **app + api (must match each other):** `SECRET_KEY`, `AUTH_SECRET`, `BETTER_AUTH_SECRET` (generate once, share). portal gets `BETTER_AUTH_SECRET` too.
- **api:** `APP_AWS_ACCESS_KEY_ID/SECRET_ACCESS_KEY/BUCKET_NAME/REGION` (placeholders OK — S3 client is lazy), `PORTAL_URL`, `TRUST_APP_URL`, `AUTH_TRUSTED_ORIGINS=http://18.61.66.186:3000,http://18.61.66.186:3002` (CORS; code reads this exact var), `BASE_URL=http://18.61.66.186:3333`, `NODE_ENV=production` (development crashes it).
- **app + portal:** `APP_AWS_*` placeholders (portal build crashes at page-data collection without them).
- **all three:** `UPSTASH_REDIS_REST_URL` + `UPSTASH_REDIS_REST_TOKEN` (real Upstash free tier — the app 500s on missing Redis, doesn't catch it), `NODE_TLS_REJECT_UNAUTHORIZED=0` (DEMO ONLY — see below).
- **Resend** (`RESEND_API_KEY` + `RESEND_FROM_*=onboarding@resend.dev`) set but email still routes through Trigger.dev, so magic-link email never sends — see login bypass.

## Postgres SSL (DEMO ONLY — insecure)

`@trycompai/db/ssl-config.ts` forces TLS for any non-localhost DB host. `comp-db` isn't localhost, so:
- Generated self-signed cert in `./pgcerts` (owned uid 999), postgres runs with `ssl=on`.
- `NODE_TLS_REJECT_UNAUTHORIZED=0` on app/api/portal to accept the self-signed cert (the `@prisma/adapter-pg` build here doesn't honor the ssl-config's `rejectUnauthorized:false`).
- **Production must use a properly-issued cert / RDS with the real CA — never `NODE_TLS_REJECT_UNAUTHORIZED=0`.**

## Login bypass (no email)

comp.ai dispatches magic-link email via Trigger.dev (skipped), so the email never arrives. To log in:
1. Click "continue" on the login page (email must match the Resend signup, `aditya@xammer.com`).
2. `docker compose exec -T db psql -U postgres -d comp -tA -c 'SELECT identifier FROM "Verification" ORDER BY "createdAt" DESC LIMIT 1;'`
3. Visit in browser: `http://18.61.66.186:3333/api/auth/magic-link/verify?token=<identifier>&callbackURL=http%3A%2F%2F18.61.66.186%3A3000`
   Token is single-use, ~1h expiry. Cookies are non-secure `local`-prefixed (getCookieDomain returns undefined for IP host), so they work over HTTP.

## Current gap / next steps

- `/v1/controls` and `/v1/policies` are **empty**: the seeder never ran (Prisma 7 module resolution in the minimal seeder container), so the SOC 2 template library was never populated and enrolling the framework instantiated 0 controls/policies.
- To populate 40 controls + 10 policies + people + vendors + evidence (plan Tasks 7-11): (1) fix/run the seeder — best fidelity, try running the seed from inside the working `api` container which has a healthy DB client; or (2) script creation via `POST /v1/controls` etc. with the API key; or (3) the UI.
- Then the XamOps-side integration (plan Tasks 12-21). Task 13 (`CompAiApiClient`) already done + committed.

## Verified API shapes (base path `/v1`, list wrapper key `data`)

- `GET /v1/frameworks` → `{"data":[{id,organizationId,frameworkId,framework:{name:"soc2",version,description}}],"count"}`
- `GET /v1/controls?perPage=N` → `{"data":[],"pageCount"}`
- `GET /v1/policies?perPage=N` → `{"data":[],"authType"}`
- `GET /v1/people?perPage=N` → `{"data":[{id,role,user:{email,name}}],"count"}`
- `GET /v1/vendors?perPage=N` → `{"data":[],"count"}`
- Auth header: `X-API-Key: comp_...`
