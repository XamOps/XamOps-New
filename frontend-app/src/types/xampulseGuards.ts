/**
 * Minimal runtime shape guards for the XamPulse contract: the keys each response type must carry.
 * Used by the contract parity test to catch field-name drift between the Java records and types/xampulse.ts.
 */
export const REQUIRED_KEYS: Record<string, string[]> = {
  XpOverview: ['demo', 'state', 'lastDataAt', 'healthScore', 'kpis', 'narrative', 'attention', 'tiles'],
  XpHostRow: ['accountId', 'instanceId', 'name', 'region', 'platform', 'state', 'health', 'score', 'cpuPct', 'memPct', 'diskPct', 'cpuSpark', 'lastSeenAt', 'agentStatus', 'collectionLevel', 'tags'],
  XpHostSummary: ['host', 'lanes', 'series', 'recentErrors', 'events', 'openIncidents'],
  XpLogRow: ['id', 'ts', 'accountId', 'region', 'instanceId', 'service', 'source', 'severity', 'body', 'attrs'],
  XpLogSearchResponse: ['rows', 'scanned', 'tookMs', 'truncated'],
  XpLogHistogramResponse: ['bucketSec', 'buckets'],
  XpLogFacetsResponse: ['approx', 'facets'],
  XpLogPatternsResponse: ['patterns'],
  XpMetricQueryResponse: ['stepSec', 'series', 'tookMs'],
  XpAlertRule: ['name', 'enabled', 'severity', 'scope', 'condition', 'grouping', 'routing'],
  XpIncident: ['id', 'accountId', 'title', 'severity', 'state', 'openedAt', 'ruleIds', 'instanceIds', 'summary', 'timeline'],
  XpInsight: ['id', 'kind', 'severity', 'title', 'body', 'accountId', 'evidence', 'confidence', 'asOf'],
  XpTrustPanel: ['collected', 'neverCollected', 'redaction', 'where', 'access', 'controls', 'permissionsDiff'],
  XpSetupOverview: ['state', 'demo', 'regions', 'instances', 'coveragePct', 'lastDataAt'],
  XpUsage: ['range', 'byDay', 'bySource', 'hotBytes', 'archiveBytes'],
  XpAccountRef: ['cloudAccountId', 'awsAccountId', 'name', 'state', 'demo'],
};

/** Returns the keys missing from `value` for the named contract type (empty means it conforms). */
export function missingKeys(type: string, value: unknown): string[] {
  const required = REQUIRED_KEYS[type];
  if (!required) return [`unknown contract type ${type}`];
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return ['not an object'];
  const rec = value as Record<string, unknown>;
  return required.filter((k) => !(k in rec));
}
