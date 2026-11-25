-- XamPulse ClickHouse V008: confirm the writer account can INSERT the tables the slice-E writers target
-- (xp_baseline via BaselineJob, xp_events via EventWriter). V006 already grants both to xp_writer_role and
-- grants the reader SELECT on xp_metrics_1h (the baselineSamples source), so these GRANTs are a defensive,
-- idempotent restatement: re-granting an already-held privilege is a no-op in ClickHouse. Roles come from V006;
-- users are created by the deployment (users.d), never here, so this file holds no credentials.
GRANT INSERT ON xp_baseline TO xp_writer_role;
GRANT INSERT ON xp_events TO xp_writer_role;
