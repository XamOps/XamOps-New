import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { useViewContext } from '../../hooks/xampulse/useViewContext';
import { useContainers } from '../../hooks/xampulse/useXamPulseQueries';
import { QueryBoundary } from '../../components/XamPulse/ui/QueryBoundary';
import { BarMeter, Card, EmptyState, SkeletonBlock } from '../../components/XamPulse/ui/ui';
import { xamPulseHostPath } from '../../constants/xampulseTabs';
import { formatAgo, formatBytes } from '../../utils/xampulse/format';

const ContainersTab: React.FC = () => {
  const ctx = useViewContext();
  const [term, setTerm] = useState('');
  const q = useContainers(ctx.effectiveAccountIds, term);
  return (
    <>
      <div className="xp-row">
        <h1 className="xp-h1" style={{ fontSize: 22 }}>Containers</h1>
        <span className="xp-muted">Docker containers on your hosts</span>
        <span className="xp-spacer" />
        <label className="xp-sr" htmlFor="xp-ct-q">Search containers</label>
        <input id="xp-ct-q" className="xp-input" placeholder="Search name or image" value={term} onChange={(e) => setTerm(e.target.value)} style={{ minWidth: 240 }} />
      </div>
      <QueryBoundary query={q} skeleton={<SkeletonBlock height={260} label="Loading containers" />}>
        {(page) => page.items.length === 0 ? (
          <Card><EmptyState title="No containers reported" body="Turn on Docker as a source in Setup and XamPulse will list containers with their CPU, memory and restarts." action={<Link className="xp-btn" to={ctx.link('/xampulse/setup')}>Open Setup</Link>} /></Card>
        ) : (
          <div className="xp-table-wrap"><table className="xp-table">
            <caption className="xp-sr">Docker containers</caption>
            <thead><tr><th scope="col">Container</th><th scope="col">Host</th><th scope="col">State</th><th scope="col" className="xp-num">CPU</th><th scope="col" className="xp-num">Memory</th><th scope="col" className="xp-num">Restarts</th><th scope="col">Seen</th></tr></thead>
            <tbody>{page.items.map((c) => (
              <tr key={`${c.instanceId}-${c.containerId}`}>
                <td><strong>{c.name}</strong><div className="xp-muted xp-mono" style={{ fontSize: 11 }}>{c.image}</div></td>
                <td><Link to={ctx.link(xamPulseHostPath(c.instanceId))}>{c.instanceId}</Link></td>
                <td>{c.state ?? '\u2014'}</td>
                <td className="xp-num"><BarMeter pct={c.cpuPct ?? null} label="CPU" /></td>
                <td className="xp-num">{formatBytes(c.memBytes)}</td>
                <td className="xp-num" style={{ color: (c.restarts ?? 0) > 0 ? 'var(--xp-warn)' : undefined }}>{c.restarts ?? '\u2014'}</td>
                <td>{formatAgo(c.lastSeenAt)}</td>
              </tr>
            ))}</tbody>
          </table></div>
        )}
      </QueryBoundary>
    </>
  );
};

export default ContainersTab;
