/** A minimal capabilities payload for the node-automation control tests (hook and bar). */
import type { CapabilitiesCluster, CapabilitiesDto, CapabilitiesManual } from '../../types/k8sAutomation';

export const CLUSTER = 'prod-eks';
export const ACCOUNT = '111111111111';

export interface CapsOptions {
  mode?: string | null;
  enabled?: boolean;
  derivedState?: 'NOT_INSTALLED' | 'OFF' | 'ANALYSIS' | 'AUTOMATED' | 'UNKNOWN';
  effectiveActing?: 'ACTING' | 'NOT_ACTING' | 'UNKNOWN' | null;
  agent?: 'CONNECTED' | 'NOT_INSTALLED';
  automationAgent?: 'CONNECTED' | 'NOT_INSTALLED';
  writeReasons?: string[];
  /** The Manual block; omitted (undefined) models an older backend. */
  manual?: CapabilitiesManual | null;
  /** Readiness map overrides, e.g. `{ 'manual-approval': { ready: true, reason: null, message: null } }`. */
  readiness?: CapabilitiesDto['readiness'];
}

export function capsDto(o: CapsOptions = {}): CapabilitiesDto {
  const writeGate = { allowed: (o.writeReasons ?? []).length === 0, reasons: o.writeReasons ?? [] };
  const nodeAutomation: CapabilitiesCluster['nodeAutomation'] = {
    enabled: o.enabled ?? true,
    mode: o.mode === undefined ? 'full' : o.mode,
    evictorAnalyseOnly: false,
    derivedState: o.derivedState ?? 'AUTOMATED',
  };
  if (o.effectiveActing !== null) nodeAutomation.effectiveActing = o.effectiveActing ?? 'ACTING';
  return {
    schemaVersion: 1,
    accountId: ACCOUNT,
    generatedAt: new Date().toISOString(),
    connection: { accessLevel: 'READ_WRITE', status: 'CONNECTED' },
    user: { canChangeAutomation: true, reasons: [], detail: null },
    writeGate,
    readiness: o.readiness ?? {},
    verticalEngine: { enabled: true },
    clusters: [{
      clusterName: CLUSTER,
      registered: true,
      connection: { accessLevel: 'READ_WRITE', status: 'CONNECTED' },
      writeGate,
      dataSourceMode: null,
      agent: { status: o.agent ?? 'CONNECTED', lastDataAt: null, version: null },
      automationAgent: { status: o.automationAgent ?? 'CONNECTED', lastSeenAt: null, version: null },
      nodeAutomation,
      dataSources: [],
      ...(o.manual !== undefined ? { manual: o.manual } : {}),
    }],
  };
}
