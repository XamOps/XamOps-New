import React, { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Play } from 'lucide-react';
import { xampulseApi, isXpDemo } from '../../../api/xampulseApi';
import { XpApiError } from '../../../api/xampulseErrors';
import { Card, ErrorState } from '../ui/ui';
import type { XpRunbookResult } from '../../../types/xampulse';

const DONE = new Set(['SUCCESS', 'FAILED', 'TIMED_OUT', 'CANCELLED']);
const STATUS: Record<XpRunbookResult['status'], string> = {
  PENDING: 'Waiting for SSM', IN_PROGRESS: 'Running', SUCCESS: 'Finished', FAILED: 'Failed', TIMED_OUT: 'Timed out', CANCELLED: 'Cancelled',
};
/** Results are polled every 2 s for at most 90 s (the script itself is limited to 60 s on the host). */
export const POLL_MS = 2000;
const POLL_LIMIT_MS = 90_000;

/**
 * Read-only checks run on the host through SSM Run Command: each button runs one fixed script, shown before it runs.
 * Off unless the deployment turns them on; then every run is audited and one runs at a time per host.
 */
export const RunbooksCard: React.FC<{ accountId: number; instanceId: string }> = ({ accountId, instanceId }) => {
  const catalog = useQuery({ queryKey: ['xp', isXpDemo() ? 'demo' : 'live', 'runbooks'], queryFn: () => xampulseApi.runbooks(), staleTime: 300_000, retry: false });
  const [running, setRunning] = useState<{ id: string; commandId: string; startedAt: number } | null>(null);
  const [result, setResult] = useState<XpRunbookResult | null>(null);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    if (!running) return undefined;
    let stop = false;
    const tick = async () => {
      try {
        const r = await xampulseApi.runbookResult(accountId, instanceId, running.commandId);
        if (stop) return;
        setResult(r);
        if (DONE.has(r.status)) { setRunning(null); return; }
        if (Date.now() - running.startedAt > POLL_LIMIT_MS) { setRunning(null); setError(new XpApiError('XP_QUERY_TIMEOUT', 'No result after 90 seconds. Check the command in the SSM console.', 0)); return; }
        window.setTimeout(() => { void tick(); }, POLL_MS);
      } catch (e) {
        if (!stop) { setRunning(null); setError(e); }
      }
    };
    const t = window.setTimeout(() => { void tick(); }, POLL_MS);
    return () => { stop = true; window.clearTimeout(t); };
  }, [running, accountId, instanceId]);

  if (catalog.isLoading || !catalog.data) return null;
  if (!catalog.data.enabled) {
    return <p className="xp-muted" style={{ fontSize: 12.5, margin: 0 }}>Read-only runbooks over SSM are turned off in this deployment.</p>;
  }
  const start = async (id: string) => {
    setError(null);
    setResult(null);
    try {
      const s = await xampulseApi.runRunbook(accountId, instanceId, id);
      setRunning({ id, commandId: s.commandId, startedAt: Date.now() });
    } catch (e) {
      setError(e);
    }
  };
  const active = catalog.data.runbooks.find((r) => r.id === (running?.id ?? result?.runbook));
  return (
    <Card title="Run a read-only check" id="xp-runbooks">
      <p className="xp-muted" style={{ margin: '0 0 10px', fontSize: 12.5 }}>
        Runs one fixed command on the host through AWS Systems Manager and shows its output. Nothing on the host is changed; every run is recorded in the audit log.
      </p>
      <ul className="xp-runbooks">{catalog.data.runbooks.map((r) => (
        <li key={r.id}>
          <button type="button" className="xp-btn xp-btn--sm" disabled={!!running} onClick={() => { void start(r.id); }} aria-describedby={`xp-rb-${r.id}`}>
            <Play size={12} aria-hidden="true" /> {r.title}
          </button>
          <span id={`xp-rb-${r.id}`} className="xp-muted" style={{ fontSize: 12 }}>{r.description}</span>
          <details><summary className="xp-muted" style={{ fontSize: 11.5 }}>Command</summary><code className="xp-mono xp-runbooks__cmd">{r.script}</code></details>
        </li>
      ))}</ul>
      {error !== null && <ErrorState error={error} compact />}
      {(running || result) && (
        <div className="xp-runbooks__out" role="status" aria-live="polite">
          <div className="xp-row" style={{ gap: 8, marginBottom: 6 }}>
            <strong>{active?.title}</strong>
            <span className="xp-chip">{running && !result ? STATUS.PENDING : result ? STATUS[result.status] : ''}</span>
            {result?.truncated && <span className="xp-muted" style={{ fontSize: 11.5 }}>Output cut at 24 KB.</span>}
          </div>
          {result && (result.output || result.error) && <pre className="xp-mono xp-runbooks__pre" tabIndex={0} aria-label={`${active?.title ?? 'Runbook'} output`}>{result.output}{result.error ? `\n${result.error}` : ''}</pre>}
        </div>
      )}
    </Card>
  );
};
