import { useCallback, useEffect, useState } from 'react';
import {
  fetchConsoleHealth, fetchConsoleLockWaits, fetchConsoleReplication,
  type DbHealthViewDto, type ConsoleFinding, type AgentLockWait, type AgentReplication,
} from '../../../hooks/useDbOpsData';

/**
 * Pillar A — the Health tab. An action list (findings as cards), not a time-series dashboard.
 * Branches the three fetch outcomes (403 → no access, >=500/0 → error, ok+empty → honest empty
 * state) and renders Locks & long transactions and Replication as sections inside this tab. House
 * style: chrome is grey; the severity dot is the only colour (red/amber/blue), no emojis.
 */
export default function HealthView({ accountId }: { accountId: string }) {
  const [view, setView] = useState<DbHealthViewDto | null>(null);
  const [lockWaits, setLockWaits] = useState<AgentLockWait[]>([]);
  const [replication, setReplication] = useState<AgentReplication[]>([]);
  const [status, setStatus] = useState<'loading' | 'ok' | 'no-access' | 'error'>('loading');
  const [dsn, setDsn] = useState<string | undefined>(undefined);

  const load = useCallback(async (selectedDsn?: string) => {
    setStatus('loading');
    const res = await fetchConsoleHealth(accountId, selectedDsn);
    if (!res.ok) {
      setStatus(res.status === 403 ? 'no-access' : 'error');
      return;
    }
    setView(res.data);
    setStatus('ok');
    const [lw, rp] = await Promise.all([fetchConsoleLockWaits(accountId), fetchConsoleReplication(accountId)]);
    setLockWaits(lw.ok ? lw.data : []);
    setReplication(rp.ok ? rp.data : []);
  }, [accountId]);

  useEffect(() => { load(dsn); }, [load, dsn]);

  if (status === 'loading') return <Centered>Loading health…</Centered>;
  if (status === 'no-access') {
    return <Centered>You don’t have access to this database’s health view.</Centered>;
  }
  if (status === 'error' || !view) {
    return (
      <div className="flex flex-col items-center justify-center gap-3 py-16 text-sm text-[rgba(0,0,0,0.55)]">
        <span>Couldn’t load the health view.</span>
        <button onClick={() => load(dsn)} className="px-3 py-1.5 rounded-md border border-[rgba(0,0,0,0.12)] text-xs hover:bg-[rgba(0,0,0,0.03)]">
          Retry
        </button>
      </div>
    );
  }

  const lwForDsn = view.dsnAlias ? lockWaits.filter(l => l.dsnAlias === view.dsnAlias) : lockWaits;
  const rpForDsn = view.dsnAlias ? replication.filter(r => r.dsnAlias === view.dsnAlias) : replication;

  return (
    <div className="space-y-5">
      {/* Status header */}
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <div className="flex items-center gap-2">
          <span className={`w-2.5 h-2.5 rounded-full ${statusDot(view.status)}`} />
          <span className="text-sm font-semibold text-[rgba(0,0,0,0.85)]">{statusLabel(view.status)}</span>
          {view.collectedAt && (
            <span className="text-[11px] text-[rgba(0,0,0,0.45)]">as of {fmtTime(view.collectedAt)}</span>
          )}
          {view.stale && (
            <span className="px-1.5 py-0.5 rounded-full text-[9px] font-bold bg-amber-100 text-amber-700">STALE</span>
          )}
        </div>
        {view.availableDsnAliases.length > 1 && (
          <label className="text-xs text-[rgba(0,0,0,0.55)] flex items-center gap-1.5">
            Datastore
            <select
              value={view.dsnAlias ?? ''}
              onChange={(e) => setDsn(e.target.value)}
              className="border border-[rgba(0,0,0,0.12)] rounded-md px-2 py-1 text-xs bg-white"
            >
              {view.availableDsnAliases.map(a => <option key={a} value={a}>{a}</option>)}
            </select>
          </label>
        )}
      </div>

      {/* Findings */}
      {view.status === 'no_data' ? (
        <EmptyState>No agent data yet. Deploy or wait for the DbOps agent to push a collection cycle.</EmptyState>
      ) : view.findings.length === 0 ? (
        <EmptyState>No issues found in the latest collection.</EmptyState>
      ) : (
        <div className="space-y-2">
          {view.findings.map(f => <FindingCard key={f.id} f={f} />)}
        </div>
      )}

      {/* Locks & long transactions */}
      <Section title="Locks & long transactions">
        {lwForDsn.length === 0 ? (
          <EmptyState>No blocked, blocking, or long-running transactions.</EmptyState>
        ) : (
          <div className="space-y-1.5">
            {lwForDsn.map(l => (
              <div key={`${l.pid}-${l.collectedAt}`} className="flex items-center gap-2 text-xs border border-[rgba(0,0,0,0.08)] rounded-md px-3 py-2">
                <span className={`w-2 h-2 rounded-full shrink-0 ${lockDot(l.category)}`} />
                <span className="font-medium text-[rgba(0,0,0,0.8)]">{lockLabel(l.category)}</span>
                <span className="text-[rgba(0,0,0,0.55)]">
                  pid {l.pid} · {l.usename || '-'} · {l.applicationName || '-'} · {l.waitEventType}/{l.waitEvent} · {Math.round(l.xactAgeSeconds)}s
                  {l.blockedByPids ? ` · blocked by ${l.blockedByPids}` : ''}
                </span>
              </div>
            ))}
          </div>
        )}
      </Section>

      {/* Replication */}
      <Section title="Replication">
        {rpForDsn.length === 0 ? (
          <EmptyState>No replication data.</EmptyState>
        ) : (
          <div className="space-y-1.5">
            {rpForDsn.map((r, i) => (
              <div key={i} className="flex items-center gap-2 text-xs border border-[rgba(0,0,0,0.08)] rounded-md px-3 py-2">
                <span className="font-medium text-[rgba(0,0,0,0.8)] capitalize">{r.role}</span>
                <span className="text-[rgba(0,0,0,0.55)]">
                  {r.applicationName ? `${r.applicationName} · ` : ''}
                  {r.state ? `${r.state} · ` : ''}
                  {lagLabel(r)}
                </span>
              </div>
            ))}
          </div>
        )}
      </Section>
    </div>
  );
}

function FindingCard({ f }: { f: ConsoleFinding }) {
  return (
    <div className="border border-[rgba(0,0,0,0.08)] rounded-md px-3 py-2.5">
      <div className="flex items-center gap-2">
        <span className={`w-2 h-2 rounded-full shrink-0 ${statusDot(f.severity)}`} />
        <span className="text-xs font-semibold text-[rgba(0,0,0,0.85)]">{f.title}</span>
      </div>
      <div className="mt-1 text-[11px] text-[rgba(0,0,0,0.6)] leading-relaxed break-words">{f.evidence}</div>
      <div className="mt-1 text-[11px] text-[rgba(0,0,0,0.45)]">{f.recommendation}</div>
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <div className="text-[11px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.45)] mb-1.5">{title}</div>
      {children}
    </div>
  );
}

function EmptyState({ children }: { children: React.ReactNode }) {
  return <div className="text-xs text-[rgba(0,0,0,0.45)] border border-dashed border-[rgba(0,0,0,0.12)] rounded-md px-3 py-4 text-center">{children}</div>;
}

function Centered({ children }: { children: React.ReactNode }) {
  return <div className="flex items-center justify-center py-16 text-sm text-[rgba(0,0,0,0.5)]">{children}</div>;
}

function statusDot(sev: string): string {
  switch (sev) {
    case 'critical': return 'bg-red-500';
    case 'warning': return 'bg-amber-400';
    case 'info': case 'collecting': return 'bg-sky-400';
    case 'ok': return 'bg-emerald-500';
    default: return 'bg-[rgba(0,0,0,0.25)]';
  }
}
function statusLabel(s: string): string {
  switch (s) {
    case 'ok': return 'Healthy';
    case 'warning': return 'Needs attention';
    case 'critical': return 'Critical';
    case 'collecting': return 'Collecting';
    case 'no_data': return 'No data';
    default: return s;
  }
}
function lockDot(category: string): string {
  return category === 'blocked' ? 'bg-red-500' : category === 'blocking' ? 'bg-amber-400' : 'bg-sky-400';
}
function lockLabel(category: string): string {
  switch (category) {
    case 'blocked': return 'Blocked';
    case 'blocking': return 'Blocking';
    case 'idle_in_transaction': return 'Idle in transaction';
    default: return 'Long-running';
  }
}
function lagLabel(r: AgentReplication): string {
  const lag = Math.max(r.writeLagSeconds ?? 0, r.flushLagSeconds ?? 0, r.replayLagSeconds ?? 0);
  if (r.writeLagSeconds == null && r.flushLagSeconds == null && r.replayLagSeconds == null) return 'no standby';
  return `lag ${lag.toFixed(1)}s`;
}
function fmtTime(iso: string): string {
  try { return new Date(iso).toLocaleString(); } catch { return iso; }
}
