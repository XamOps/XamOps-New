import React from 'react';
import { Link } from 'react-router-dom';
import { CircleCheck, CircleHelp, Search, TriangleAlert } from 'lucide-react';
import type { XpHypothesis, XpInvestigation, XpPeerMetric, XpPeers } from '../../../types/xampulse';
import { Card } from '../ui/ui';
import { formatPct, formatRate } from '../../../utils/xampulse/format';

const CONFIDENCE: Record<string, string> = { HIGH: 'Strong evidence', MEDIUM: 'Some evidence', LOW: 'Weak evidence' };
const SECTION_LABEL: Record<string, string> = {
  aws: 'Open AWS', changes: 'Open Changes', cpu: 'Open CPU', memory: 'Open Memory', storage: 'Open Storage', network: 'Open Network',
  activity: 'Open Logs & events', peers: 'See peers',
};

/** A peer value with its unit: percentages, byte rates and per-minute counts. */
export function peerValue(v: number | null | undefined, unit: string): string {
  if (v === null || v === undefined) return '—';
  if (unit === '%') return formatPct(v, v < 10 ? 1 : 0);
  if (unit === 'B/s') return formatRate(v);
  if (unit === '/min') return `${v < 10 ? v.toFixed(1) : Math.round(v)}/min`;
  return String(v);
}

const VERDICT: Record<XpPeerMetric['verdict'], string> = {
  HIGHER: 'Higher than peers', LOWER: 'Lower than peers', IN_LINE: 'In line', NO_DATA: 'No data on this host', TOO_FEW: 'Too few peers report it',
};

export const PeersTable: React.FC<{ p: XpPeers }> = ({ p }) => (
  <Card title="Compared with its peers" id="xp-peers">
    {p.peers.length === 0 ? <p className="xp-muted" style={{ margin: 0 }}>{p.note ?? 'No peers to compare with.'}</p> : (
      <>
        <p className="xp-muted" style={{ margin: '0 0 8px', fontSize: 12.5 }}>
          {p.peers.length} {p.peers.length === 1 ? 'peer' : 'peers'}: {p.basisLabel}. Outliers need a robust z-score of 3.5 or more and a real difference, so one odd peer cannot hide this host and small differences between idle hosts are not flagged.
        </p>
        <div className="xp-table-wrap"><table className="xp-table">
          <caption className="xp-sr">This host against its peers</caption>
          <thead><tr><th scope="col">Signal</th><th scope="col" className="xp-num">This host</th><th scope="col" className="xp-num">Peers (median)</th><th scope="col" className="xp-num">Peers range</th><th scope="col">Verdict</th></tr></thead>
          <tbody>{p.metrics.map((m) => {
            const odd = m.verdict === 'HIGHER' || m.verdict === 'LOWER';
            return (
              <tr key={m.key} className={odd ? 'xp-peers__odd' : undefined}>
                <th scope="row" style={{ fontWeight: 450 }}>{m.label}{Object.keys(m.peerValues).length > 0 && <details className="xp-peer-values"><summary>Peer values</summary>{Object.entries(m.peerValues).map(([id, value]) => <div key={id}><span className="xp-mono">{p.peers.find((peer) => peer.instanceId === id)?.name ?? id}</span>: {peerValue(value, m.unit)}</div>)}</details>}</th>
                <td className="xp-num">{peerValue(m.value, m.unit)}</td>
                <td className="xp-num">{peerValue(m.peerMedian, m.unit)}</td>
                <td className="xp-num">{m.peerMin === null ? '—' : `${peerValue(m.peerMin, m.unit)} – ${peerValue(m.peerMax, m.unit)}`}</td>
                <td><span className={`xp-chip${odd ? ' xp-chip--warn' : ''}`}>{VERDICT[m.verdict]}{odd && m.indicative ? ' (2 peers: indicative)' : ''}</span></td>
              </tr>
            );
          })}</tbody>
        </table></div>
        <p className="xp-muted" style={{ fontSize: 11.5, margin: '8px 0 0' }}>Peers: {p.peers.map((x) => x.name ?? x.instanceId).join(', ')}</p>
      </>
    )}
  </Card>
);

const Evidence: React.FC<{ h: XpHypothesis; onSection: (s: string) => void; logsLink: (q: string) => string }> = ({ h, onSection, logsLink }) => (
  <>
    <ul className="xp-inv__evidence">{h.evidence.map((e) => <li key={e}>{e}</li>)}</ul>
    {(h.section || h.logsQuery) && h.status === 'SUPPORTED' && (
      <div className="xp-row" style={{ gap: 6, marginTop: 6 }}>
        {h.section && <button type="button" className="xp-btn xp-btn--sm" onClick={() => onSection(h.section as string)}>{SECTION_LABEL[h.section] ?? 'Open'}</button>}
        {h.logsQuery && <Link className="xp-btn xp-btn--sm" to={logsLink(h.logsQuery)}>Open in Logs</Link>}
      </div>
    )}
  </>
);

/**
 * Investigate: the summary, then what the evidence supports (strongest first), what was ruled out, and what this
 * host cannot tell. Every line is a measurement; nothing is called a cause.
 */
export const InvestigatePanel: React.FC<{ inv: XpInvestigation; onSection: (s: string) => void; logsLink: (q: string) => string }> = ({ inv, onSection, logsLink }) => {
  const supported = inv.hypotheses.filter((h) => h.status === 'SUPPORTED');
  const ruledOut = inv.hypotheses.filter((h) => h.status === 'RULED_OUT');
  const unknown = inv.hypotheses.filter((h) => h.status === 'NOT_MEASURABLE');
  return (
    <div className="xp-inv">
      <div className="xp-banner xp-inv__summary" role="status"><Search size={15} aria-hidden="true" /><span>{inv.summary}<span className="xp-status-line">Evaluated {new Date(inv.from).toLocaleString()} to {new Date(inv.to).toLocaleString()}</span></span></div>
      <Card title={`Supported by the evidence (${supported.length})`} id="xp-inv-sup">
        {supported.length === 0 ? <p className="xp-muted" style={{ margin: 0 }}>None of the usual explanations is supported by what was measured in this range.</p> : (
          <ol className="xp-inv__list">{supported.map((h, i) => (
            <li key={h.code} className="xp-inv__item xp-inv__item--sup">
              <div className="xp-row" style={{ gap: 8 }}>
                <TriangleAlert size={15} aria-hidden="true" className="xp-inv__ic--sup" />
                <strong>{i === 0 ? 'Strongest lead: ' : ''}{h.title}</strong>
                <span className="xp-spacer" />
                {h.confidence && <span className={`xp-chip xp-inv__conf--${h.confidence.toLowerCase()}`}>{CONFIDENCE[h.confidence]}</span>}
              </div>
              <Evidence h={h} onSection={onSection} logsLink={logsLink} />
            </li>
          ))}</ol>
        )}
      </Card>
      <div className="xp-grid xp-grid--2">
        <Card title={`Ruled out (${ruledOut.length})`} id="xp-inv-out">
          {ruledOut.length === 0 ? <p className="xp-muted" style={{ margin: 0 }}>Nothing could be ruled out with the data in this range.</p> : (
            <ul className="xp-inv__list">{ruledOut.map((h) => (
              <li key={h.code} className="xp-inv__item">
                <details><summary><CircleCheck size={14} aria-hidden="true" className="xp-inv__ic--ok" /> {h.title}</summary><Evidence h={h} onSection={onSection} logsLink={logsLink} /></details>
              </li>
            ))}</ul>
          )}
        </Card>
        <Card title={`Cannot tell here (${unknown.length})`} id="xp-inv-unk">
          {unknown.length === 0 ? <p className="xp-muted" style={{ margin: 0 }}>Every check had the data it needed.</p> : (
            <ul className="xp-inv__list">{unknown.map((h) => (
              <li key={h.code} className="xp-inv__item">
                <div className="xp-row" style={{ gap: 6 }}><CircleHelp size={14} aria-hidden="true" className="xp-muted" /> {h.title}</div>
                <p className="xp-muted" style={{ margin: '2px 0 0 20px', fontSize: 12 }}>{h.evidence[0]}</p>
              </li>
            ))}</ul>
          )}
        </Card>
      </div>
      {inv.peers && <PeersTable p={inv.peers} />}
    </div>
  );
};
