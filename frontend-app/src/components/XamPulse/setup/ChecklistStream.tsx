import React, { useEffect, useState } from 'react';
import { watchChecklist } from '../../../api/xampulseSetupApi';
import { isXpDemo } from '../../../api/xampulseApi';
import type { XpChecklistCheck } from '../../../types/xampulse';
import { Card } from '../ui/ui';

const ICON: Record<XpChecklistCheck['state'], string> = { OK: '\u2713', WAIT: '\u25CC', FAIL: '\u2716' };
const TONE: Record<XpChecklistCheck['state'], string> = { OK: 'ok', WAIT: 'info', FAIL: 'crit' };
const WORD: Record<XpChecklistCheck['state'], string> = { OK: 'Done', WAIT: 'Waiting', FAIL: 'Needs a fix' };

/** Connected means every check is OK. The definition lives here and in the server verify call. */
export const isConnected = (checks: XpChecklistCheck[]): boolean => checks.length > 0 && checks.every((c) => c.state === 'OK');

/** Live checklist: server-sent events with a 3 s polling fallback. */
export const ChecklistStream: React.FC<{ accountId: number; onConnected?: () => void }> = ({ accountId, onConnected }) => {
  const [checks, setChecks] = useState<XpChecklistCheck[]>([]);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    setError(null);
    // Demo mode has no SSE endpoint: always poll.
    return watchChecklist(accountId, (c) => { setChecks(c); setError(null); }, setError, !isXpDemo());
  }, [accountId]);
  const done = isConnected(checks);
  useEffect(() => { if (done) onConnected?.(); }, [done, onConnected]);
  return (
    <Card title={done ? 'Connected' : 'Checking your setup'} id="xp-check">
      <ol style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: 8 }} aria-live="polite">
        {checks.length === 0 && !error && <li className="xp-muted">Waiting for the first check</li>}
        {checks.map((c) => (
          <li key={`${c.id}-${c.region ?? ''}`} className="xp-row">
            <span className={`xp-pill xp-pill--${TONE[c.state]}`}><span aria-hidden="true">{ICON[c.state]}</span>{WORD[c.state]}</span>
            <span>{c.label}{c.region ? ` (${c.region})` : ''}</span>
            {c.state !== 'OK' && c.hint && <span className="xp-muted" style={{ fontSize: 12.5 }}>{c.hint}{c.docUrl && <> <a href={c.docUrl} target="_blank" rel="noopener noreferrer">Learn more</a></>}</span>}
          </li>
        ))}
      </ol>
      {error && <p className="xp-err" role="alert">Could not read the checklist: {error}. Retrying.</p>}
      {done && <p style={{ marginBottom: 0 }}>Connected means: stack complete, bucket writable, agents enrolled and first data received.</p>}
    </Card>
  );
};
