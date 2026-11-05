/**
 * DbOps sub-feature UI flags.
 *
 * These mirror the backend defaults in DbOpsFeatureFlags (bound to the
 * `xamops.dbops.<name>.enabled` properties, all default false). The backend
 * sends no data for a disabled feature; these consts hide the matching UI so
 * the reassuring "all-clear" empty states never render either.
 *
 * Keep these in sync with the backend: if a backend flag is ever flipped on,
 * flip the matching const here in the same change.
 */

export const DBOPS_IDLE_UI_ENABLED = false;
export const DBOPS_RIGHTSIZING_UI_ENABLED = false;
export const DBOPS_HEALTH_UI_ENABLED = false;
export const DBOPS_MONGO_UI_ENABLED = false;
export const DBOPS_REDIS_UI_ENABLED = false;

/**
 * Phase 2 DbOps console — the Health view and the access/FK cytoscape graph tabs. Mirrors the
 * backend `xamops.dbops.console.enabled` flag, which is turned on for the UAT profile only (the
 * backend default stays false). Enabled here globally so the console UI renders wherever the
 * backend serves it; outside UAT the backend answers empty documents until its own flag is set.
 */
export const DBOPS_CONSOLE_UI_ENABLED = true;
