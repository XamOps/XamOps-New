import React, { useId } from 'react';
import { useNavigate } from 'react-router-dom';
import type { DashboardEnvironment, DashboardSubCategory } from '../../../types/dspmPages';
import { DspmProviderIcon } from '../DspmIcons';
import { driveIconKey } from '../datastores/datastoreVisuals';
import { ConsoleOnlyButton } from '../datastores/ui/ConsoleOnly';
import { InfoIcon } from './DashboardParts';
import {
  ENVIRONMENT_NAMES, ENVIRONMENT_TOOLTIPS, dashboardBarLink, formatCompactCount,
} from './dashboardModel';

/** The icon key of a bar: SaaS bars are drive types (Gmail, Google Drive), every other bar its own code. */
const barIconKey = (environment: DashboardEnvironment, key: string) =>
  environment.id === 'saas' ? driveIconKey(key) : key;

const BarRow: React.FC<{
  environment: DashboardEnvironment;
  bar: DashboardSubCategory;
  max: number;
}> = ({ environment, bar, max }) => {
  const navigate = useNavigate();
  const width = max > 0 ? `${(bar.sensitiveRecords / max) * 100}%` : '0%';
  const count = bar.sensitiveRecords.toLocaleString('en-US');
  const body = (
    <>
      <DspmProviderIcon provider={barIconKey(environment, bar.key)} size={14} />
      <span className="w-24 flex-shrink-0 truncate text-left" title={bar.label}>{bar.label}</span>
      <span className="h-1.5 flex-1 overflow-hidden rounded-full bg-[rgba(0,0,0,0.06)]" aria-hidden="true">
        <span className="block h-full rounded-full bg-sky-500" style={{ width }} />
      </span>
      <span className="w-12 flex-shrink-0 text-right tabular-nums text-[rgba(0,0,0,0.7)]" title={count}>
        {formatCompactCount(bar.sensitiveRecords)}
      </span>
    </>
  );
  const rowClass = 'flex w-full items-center gap-2 rounded-md px-1.5 py-1 text-xs text-[rgba(0,0,0,0.75)]';
  // SaaS bars are drive types, which no Datastores filter selects: they open nothing.
  if (environment.filter === null) {
    return <li className={rowClass}>{body}</li>;
  }
  const filter = environment.filter;
  return (
    <li>
      <button
        type="button"
        onClick={() => navigate(dashboardBarLink(environment.id, filter, bar.key))}
        aria-label={`${bar.label}: ${count} sensitive records. Show these datastores`}
        className={`${rowClass} transition-colors hover:bg-[rgba(0,0,0,0.04)] focus:outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.25)]`}
      >
        {body}
      </button>
    </li>
  );
};

const EnvironmentCard: React.FC<{ environment: DashboardEnvironment }> = ({ environment }) => {
  const titleId = useId();
  const name = ENVIRONMENT_NAMES[environment.id] ?? environment.id;
  const tooltip = ENVIRONMENT_TOOLTIPS[environment.id];
  const max = environment.subCategories.reduce((m, bar) => Math.max(m, bar.sensitiveRecords), 0);
  return (
    <article aria-labelledby={titleId} className="flex min-w-0 flex-col gap-3 rounded-2xl border border-[rgba(0,0,0,0.08)] bg-white p-4">
      <header className="flex items-center gap-1.5">
        <h3 id={titleId} className="text-xs font-semibold text-[rgba(0,0,0,0.7)]">{name}</h3>
        {tooltip && <InfoIcon text={tooltip} />}
      </header>
      {environment.connected ? (
        <>
          <div>
            <p
              className="text-2xl font-bold tabular-nums text-[rgba(0,0,0,0.88)]"
              title={environment.sensitiveRecords.toLocaleString('en-US')}
            >
              {formatCompactCount(environment.sensitiveRecords)}
            </p>
            <p className="text-xs text-[rgba(0,0,0,0.5)]">Sensitive Records</p>
          </div>
          {environment.subCategories.length > 0 && (
            <ul aria-label={`${name} breakdown`} className="space-y-0.5">
              {environment.subCategories.map(bar => (
                <BarRow key={bar.key} environment={environment} bar={bar} max={max} />
              ))}
            </ul>
          )}
          {environment.partial && (
            <p className="text-[11px] text-[rgba(0,0,0,0.45)]">Based on the first 1,000 datastores.</p>
          )}
        </>
      ) : (
        <div className="flex flex-1 flex-col items-center justify-center gap-1 py-4 text-center">
          <p className="text-sm font-semibold text-[rgba(0,0,0,0.7)]">{name} Not Connected</p>
          <p className="text-xs text-[rgba(0,0,0,0.5)]">Connect to see {name} Data</p>
          <div className="mt-2">
            <ConsoleOnlyButton variant="secondary">Connect</ConsoleOnlyButton>
          </div>
        </div>
      )}
    </article>
  );
};

/**
 * "Where does sensitive data live": one card per environment, in the response's order (Cloud, SaaS,
 * Endpoint, On Prem). The big number is the summary's sensitive records; each bar sums the records of one
 * provider (OS on Endpoint, drive type on SaaS) and, except on SaaS, opens the Datastores tab filtered by it.
 * The console's "vs last week" line is left out (no public source gives its figure, GAP-19).
 */
const EnvironmentCards: React.FC<{ environments: DashboardEnvironment[] }> = ({ environments }) => (
  <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-4">
    {environments.map(environment => <EnvironmentCard key={environment.id} environment={environment} />)}
  </div>
);

export default EnvironmentCards;
