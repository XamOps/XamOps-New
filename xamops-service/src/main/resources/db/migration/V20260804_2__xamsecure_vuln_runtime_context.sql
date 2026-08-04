-- Promote Upwind vulnerability-finding fields from raw_payload JSON into real columns.
--
-- Every one of these was already arriving in the v1 payload and being discarded on all
-- ~126,808 findings. Two consequences:
--   1. Upwind's actual differentiator -- which vulnerable code is loaded and reachable at
--      runtime -- could not be surfaced, so the Vulnerabilities tab showed raw installed
--      counts with no way to narrow to what matters.
--   2. The funnel queries that DID need these values cast raw_payload (TEXT) to jsonb per
--      row at query time, which cannot use an index. Real columns fix both.
--
-- Measured distribution over a 12,000-finding sample (2026-08-04):
--   package_in_use 93.0%, internet_exposed 37.9%, active_communication 16.2%,
--   exploitable 24.7%, exploitable_functions_in_use 0.42%.

ALTER TABLE upwind_vulnerability_finding
    -- runtime exposure
    ADD COLUMN IF NOT EXISTS package_in_use               BOOLEAN,
    ADD COLUMN IF NOT EXISTS internet_exposed             BOOLEAN,
    ADD COLUMN IF NOT EXISTS active_communication         BOOLEAN,
    ADD COLUMN IF NOT EXISTS exploitable_functions_in_use BOOLEAN,
    ADD COLUMN IF NOT EXISTS exploitable_functions        VARCHAR(1024),
    -- package / image context
    ADD COLUMN IF NOT EXISTS package_name                 VARCHAR(255),
    ADD COLUMN IF NOT EXISTS package_version              VARCHAR(255),
    ADD COLUMN IF NOT EXISTS package_type                 VARCHAR(64),
    ADD COLUMN IF NOT EXISTS package_framework            VARCHAR(64),
    ADD COLUMN IF NOT EXISTS image_name                   VARCHAR(255),
    ADD COLUMN IF NOT EXISTS image_tag                    VARCHAR(255),
    ADD COLUMN IF NOT EXISTS image_uri                    VARCHAR(512),
    ADD COLUMN IF NOT EXISTS image_os_name                VARCHAR(255),
    ADD COLUMN IF NOT EXISTS image_os_version             VARCHAR(64),
    -- kubernetes context
    ADD COLUMN IF NOT EXISTS cluster_id                   VARCHAR(255),
    ADD COLUMN IF NOT EXISTS cluster_name                 VARCHAR(255),
    ADD COLUMN IF NOT EXISTS namespace                    VARCHAR(255),
    -- scoring / provenance
    ADD COLUMN IF NOT EXISTS epss_severity                VARCHAR(32),
    ADD COLUMN IF NOT EXISTS cvss_v2_score                VARCHAR(16),
    ADD COLUMN IF NOT EXISTS cvss_v2_severity             VARCHAR(32),
    ADD COLUMN IF NOT EXISTS cvss_v4_score                VARCHAR(16),
    ADD COLUMN IF NOT EXISTS cvss_v4_severity             VARCHAR(32),
    ADD COLUMN IF NOT EXISTS known_exploit_sources        VARCHAR(256),
    ADD COLUMN IF NOT EXISTS cve_first_seen_time          TIMESTAMP,
    ADD COLUMN IF NOT EXISTS source                       VARCHAR(64);

-- One-time backfill. raw_payload already holds all of this for every existing row, so the
-- new columns are populated immediately rather than waiting up to 6h for the next full sync
-- (and the runtime views would otherwise read as all-null in the meantime).
--
-- Guarded on raw_payload being valid JSON: a malformed row would otherwise abort the whole
-- migration. Flyway runs this exactly once; re-running by hand simply rewrites identical
-- values, so there is no "already done" guard to get subtly wrong.
--
-- Absent values stay NULL rather than becoming false, matching UpwindSyncService: "Upwind did
-- not report this" must remain distinguishable from "Upwind reported no". Hence the
-- jsonb_typeof checks rather than coalescing a missing key to an empty array.
UPDATE upwind_vulnerability_finding SET
    package_in_use               = (cast(raw_payload as jsonb) -> 'package'  ->> 'in_use') = 'true',
    internet_exposed             = (cast(raw_payload as jsonb) -> 'resource' -> 'internet_exposure'
                                      -> 'ingress' ->> 'exposed') = 'true',
    active_communication         = (cast(raw_payload as jsonb) -> 'resource' -> 'internet_exposure'
                                      -> 'ingress' ->> 'active_communication') = 'true',
    exploitable_functions_in_use = CASE WHEN jsonb_typeof(cast(raw_payload as jsonb)
                                             -> 'vulnerability' -> 'exploitable_functions_in_use') = 'array'
                                       THEN jsonb_array_length(cast(raw_payload as jsonb)
                                             -> 'vulnerability' -> 'exploitable_functions_in_use') > 0
                                  END,
    exploitable_functions        = CASE WHEN jsonb_typeof(cast(raw_payload as jsonb)
                                             -> 'vulnerability' -> 'exploitable_functions_in_use') = 'array'
                                       THEN nullif(left((SELECT string_agg(x, ',')
                                             FROM jsonb_array_elements_text(cast(raw_payload as jsonb)
                                               -> 'vulnerability' -> 'exploitable_functions_in_use') AS t(x)),
                                             1024), '')
                                  END,
    known_exploit_sources        = CASE WHEN jsonb_typeof(cast(raw_payload as jsonb)
                                             -> 'vulnerability' -> 'known_exploit_sources') = 'array'
                                       THEN nullif(left((SELECT string_agg(x, ',')
                                             FROM jsonb_array_elements_text(cast(raw_payload as jsonb)
                                               -> 'vulnerability' -> 'known_exploit_sources') AS t(x)),
                                             256), '')
                                  END,
    cve_first_seen_time          = (cast(raw_payload as jsonb) -> 'vulnerability'
                                      ->> 'cve_first_seen_time')::timestamptz,
    package_name                 = cast(raw_payload as jsonb) -> 'package' ->> 'name',
    package_version              = cast(raw_payload as jsonb) -> 'package' ->> 'version',
    package_type                 = cast(raw_payload as jsonb) -> 'package' ->> 'type',
    package_framework            = cast(raw_payload as jsonb) -> 'package' ->> 'framework',
    image_name                   = cast(raw_payload as jsonb) -> 'image' ->> 'name',
    image_tag                    = cast(raw_payload as jsonb) -> 'image' ->> 'tag',
    image_uri                    = left(cast(raw_payload as jsonb) -> 'image' ->> 'uri', 512),
    image_os_name                = cast(raw_payload as jsonb) -> 'image' ->> 'os_name',
    image_os_version             = cast(raw_payload as jsonb) -> 'image' ->> 'os_version',
    cluster_id                   = cast(raw_payload as jsonb) -> 'resource' ->> 'cluster_id',
    cluster_name                 = cast(raw_payload as jsonb) -> 'resource' ->> 'cluster_name',
    namespace                    = cast(raw_payload as jsonb) -> 'resource' ->> 'namespace',
    epss_severity                = cast(raw_payload as jsonb) -> 'vulnerability' ->> 'epss_severity',
    cvss_v2_score                = cast(raw_payload as jsonb) -> 'vulnerability' ->> 'nvd_cvss_v2_score',
    cvss_v2_severity             = cast(raw_payload as jsonb) -> 'vulnerability' ->> 'nvd_cvss_v2_severity',
    cvss_v4_score                = cast(raw_payload as jsonb) -> 'vulnerability' ->> 'nvd_cvss_v4_score',
    cvss_v4_severity             = cast(raw_payload as jsonb) -> 'vulnerability' ->> 'nvd_cvss_v4_severity',
    source                       = cast(raw_payload as jsonb) ->> 'source'
WHERE raw_payload IS NOT NULL
  AND raw_payload ~ '^\s*\{';

-- The runtime-exposure narrowing is the point of these columns, so index the combination the
-- funnel and the "runtime exposure" filter select on.
CREATE INDEX IF NOT EXISTS idx_upwind_vuln_runtime
    ON upwind_vulnerability_finding (cloud_account_id, package_in_use, internet_exposed);

CREATE INDEX IF NOT EXISTS idx_upwind_vuln_cluster
    ON upwind_vulnerability_finding (cloud_account_id, cluster_id);
