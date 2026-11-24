import React from 'react';
import { Icon } from '@iconify/react';
import { riskVisual } from '../../Dspm/datastores/datastoreVisuals';
import { SEVERITY_LEVELS, severityName, type FindingsSummary } from './findingsModel';

const CARD_LABEL = 'text-[11px] font-semibold text-[rgba(0,0,0,0.5)]';
const FIGURE = 'text-xl font-bold leading-7 tabular-nums text-[rgba(0,0,0,0.88)]';

type State = 'loading' | 'failed' | 'ready';

const Figure: React.FC<{ value: number; state: State }> = ({ value, state }) => {
  if (state === 'loading') {
    return (
      <span className="block h-7 w-12 animate-pulse rounded bg-[rgba(0,0,0,0.06)]">
        <span className="sr-only">Loading</span>
      </span>
    );
  }
  if (state === 'failed') return <span className={`${FIGURE} text-[rgba(0,0,0,0.35)]`}>-</span>;
  return <span className={FIGURE}>{value.toLocaleString('en-US')}</span>;
};

const Card: React.FC<{ label: string; icon: string; children: React.ReactNode }> = ({ label, icon, children }) => (
  <div role="group" aria-label={label} className="rounded-xl border border-[rgba(0,0,0,0.08)] bg-white px-4 py-3">
    <div className="flex items-center gap-1.5">
      <Icon icon={icon} width={16} className="text-[rgba(0,0,0,0.45)]" />
      <span className={CARD_LABEL}>{label}</span>
    </div>
    <div className="mt-1.5 flex min-h-7 flex-wrap items-end justify-between gap-2">{children}</div>
  </div>
);

interface Props {
  summary: FindingsSummary;
  loading: boolean;
  /** Loading failed: the figures are unknown, so they show "-" rather than zeros. */
  failed: boolean;
}

/**
 * The analytics row of the XamLens Misconfigurations page, "Total Affected Resources" and "Open
 * Misconfigurations", with figures: XamLens greys both because Matters has no project totals, while
 * every finding here is already loaded. The figures follow the search and filters, as the old KPI
 * cards followed the compliance filter. The second card adds a box per severity and the finding count.
 */
const FindingsAnalytics: React.FC<Props> = ({ summary, loading, failed }) => {
  const state: State = loading ? 'loading' : failed ? 'failed' : 'ready';
  return (
    <div className="grid gap-3 sm:grid-cols-2 xl:max-w-3xl">
      <Card label="Total Affected Resources" icon="mdi:database-outline">
        <Figure value={summary.affectedResources} state={state} />
      </Card>
      <Card label="Open Misconfigurations" icon="mdi:shield-alert-outline">
        <span className="flex items-baseline gap-2">
          <Figure value={summary.misconfigurations} state={state} />
          {state === 'ready' && (
            <span className="text-[11px] text-[rgba(0,0,0,0.45)]">
              {summary.findings.toLocaleString('en-US')} {summary.findings === 1 ? 'finding' : 'findings'}
            </span>
          )}
        </span>
        {state === 'ready' && (
          <span className="flex flex-wrap items-center gap-1.5">
            {SEVERITY_LEVELS.map(level => {
              const visual = riskVisual(level);
              const count = summary.bySeverity[level];
              return (
                <span
                  key={level}
                  title={`${severityName(level)}: ${count.toLocaleString('en-US')}`}
                  className={`inline-flex items-center gap-0.5 rounded-md border px-1.5 py-0.5 text-[11px] font-semibold tabular-nums ${visual.box}`}
                >
                  <Icon icon={visual.icon} width={12} />
                  <span className="sr-only">{severityName(level)} </span>
                  {count.toLocaleString('en-US')}
                </span>
              );
            })}
          </span>
        )}
      </Card>
    </div>
  );
};

export default FindingsAnalytics;
