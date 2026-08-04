-- Single-runner lease for the Upwind sync.
--
-- live.xamops.com runs behind an ASG, so every instance boots the same image with
-- upwind.sync.enabled=true and its own @Scheduled timers. Without coordination each
-- cadence fires once per instance: duplicated Upwind API load, and concurrent runs
-- interleaving around the mark-resolved reconciliation pass.
--
-- A lease row per domain means exactly one instance syncs at a time. expires_at makes it
-- self-healing: if the holder dies mid-run the lease simply ages out instead of wedging
-- the sync permanently.

CREATE TABLE upwind_sync_lock (
    name VARCHAR(64) PRIMARY KEY,
    holder VARCHAR(160),
    acquired_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL
);
