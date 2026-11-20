import React, { useId, useMemo, useState } from 'react';
import ReactDOM from 'react-dom';
import { Icon } from '@iconify/react';
import type { SecurityFinding } from '../../../types/security';
import { findingKey } from '../../../hooks/useSecurityData';
import { useSidePanel } from '../../Dspm/datastores/drawer/EscapeStack';
import { LevelLabel } from '../../Dspm/datastores/ui/Chips';
import { CopyButton } from '../../Dspm/datastores/ui/CopyButton';
import { guideFor, liveLearnMore, resourceTypeFor } from './checkGuides';
import { CategoryIcon, NeutralChip } from './FindingChips';
import { DetailCard } from './FindingDetails';
import Remediation from './Remediation';
import {
  findingsOnResource, frameworkLabel, groupFindings, type SecurityMisconfiguration,
} from './findingsModel';

interface Props {
  /** The finding whose resource was clicked: its id, region and category name the resource. */
  resource: SecurityFinding;
  /** Every finding on the page; the drawer lists the ones on this resource. */
  findings: readonly SecurityFinding[];
  /** The misconfiguration the drawer was opened from, listed first and open. */
  fromKey: string;
  accountId: string | null;
  /** The control that opened the drawer; focus goes back to it on close. */
  returnFocus: HTMLElement | null;
  /** Should be stable: the drawer is an entry on the parent drawer's Escape stack. */
  onClose: () => void;
  onResolve: (finding: SecurityFinding) => void;
  resolvingKeys: readonly string[];
}

const DT = 'text-[rgba(0,0,0,0.5)]';
const DD = 'min-w-0 break-words text-[rgba(0,0,0,0.8)]';

/** One misconfiguration on the resource, collapsed to its title row until opened. */
const ResourceMisconfiguration: React.FC<{
  row: SecurityMisconfiguration;
  accountId: string | null;
  open: boolean;
  onToggle: () => void;
  onResolve: (finding: SecurityFinding) => void;
  resolving: boolean;
}> = ({ row, accountId, open, onToggle, onResolve, resolving }) => {
  const bodyId = useId();
  const finding = row.findings[0];
  const guide = guideFor(row.title, { resourceId: finding.resourceId, region: finding.region, accountId });
  const framework = frameworkLabel(row);
  return (
    <li className="rounded-xl border border-[rgba(0,0,0,0.08)]">
      <div className="flex items-start gap-2 px-3 py-2.5">
        <button
          type="button"
          onClick={onToggle}
          aria-expanded={open}
          aria-controls={bodyId}
          className="flex min-w-0 flex-1 items-start gap-2 text-left"
        >
          <Icon
            icon={open ? 'mdi:chevron-down' : 'mdi:chevron-right'}
            width={16}
            className="mt-0.5 flex-shrink-0 text-[rgba(0,0,0,0.4)]"
          />
          <span className="min-w-0 flex-1 space-y-1">
            <span className="block break-words text-xs font-semibold text-[rgba(0,0,0,0.85)]">{row.title || '-'}</span>
            <span className="flex flex-wrap items-center gap-2">
              <LevelLabel level={row.severity} label={row.severityLabel} />
              {framework !== null && <NeutralChip label={framework} />}
            </span>
          </span>
        </button>
        <button
          type="button"
          onClick={() => onResolve(finding)}
          disabled={resolving}
          title="Mark fixed. If a scan finds it again, it comes back."
          aria-label={`Mark fixed: ${row.title} on ${finding.resourceId}`}
          className="inline-flex flex-shrink-0 items-center gap-1.5 whitespace-nowrap rounded border border-[rgba(0,0,0,0.12)] px-2.5 py-1 text-xs font-semibold text-[rgba(0,0,0,0.6)] transition-colors hover:border-emerald-300 hover:bg-emerald-50 hover:text-emerald-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-1 disabled:cursor-not-allowed disabled:opacity-50"
        >
          <Icon icon={resolving ? 'mdi:loading' : 'mdi:check-circle-outline'} width={14} className={resolving ? 'animate-spin' : ''} />
          Mark fixed
        </button>
      </div>
      {open && (
        <div id={bodyId} className="space-y-3 border-t border-[rgba(0,0,0,0.06)] px-4 py-3">
          <div className="space-y-1">
            <h4 className="text-[11px] font-semibold text-[rgba(0,0,0,0.55)]">Description</h4>
            <p className="whitespace-pre-line break-words text-xs leading-relaxed text-[rgba(0,0,0,0.75)]">
              {row.description ?? guide?.description ?? row.title}
            </p>
          </div>
          <div className="space-y-1">
            <h4 className="text-[11px] font-semibold text-[rgba(0,0,0,0.55)]">Remediation</h4>
            <Remediation
              text={row.remediation}
              guide={guide}
              learnMore={guide?.learnMore ?? liveLearnMore(row.framework, row.controlId)}
            />
          </div>
        </div>
      )}
    </li>
  );
};

/**
 * One affected resource, opened from a misconfiguration's Affected Resources: a second drawer over the
 * first, as XamLens opens a datastore from a misconfiguration. It shows the resource (id, type, region,
 * account and its AWS console page) and every misconfiguration on it, not only the one it was opened
 * from, each with its remediation and the CLI fix filled in for this resource, and Mark fixed.
 *
 * It is a layer on the first drawer's Escape stack (`useSidePanel`): Escape and the overlay close it
 * alone, and focus goes back to the resource that opened it. The list follows the page's findings, so a
 * resource marked fixed leaves it at once.
 */
const ResourceDrawer: React.FC<Props> = ({
  resource, findings, fromKey, accountId, returnFocus, onClose, onResolve, resolvingKeys,
}) => {
  const { closeButton, close } = useSidePanel(returnFocus, onClose);
  const titleId = useId();
  const rows = useMemo(() => {
    const grouped = groupFindings(findingsOnResource(findings, resource));
    const from = grouped.findIndex(row => row.key === fromKey);
    return from <= 0 ? grouped : [grouped[from], ...grouped.slice(0, from), ...grouped.slice(from + 1)];
  }, [findings, resource, fromKey]);
  const [openKeys, setOpenKeys] = useState<ReadonlySet<string>>(() => new Set([fromKey]));
  const toggle = (key: string) => setOpenKeys(prev => {
    const next = new Set(prev);
    if (next.has(key)) next.delete(key); else next.add(key);
    return next;
  });

  const resourceId = resource.resourceId || '-';
  const region = resource.region?.trim() || null;
  const category = resource.category?.trim() || null;
  const resourceType = resourceTypeFor(resource.description, category);
  const consoleUrl = guideFor(resource.description, { resourceId: resource.resourceId, region, accountId })?.consoleUrl ?? null;

  return ReactDOM.createPortal(
    <>
      <div className="fixed inset-0 z-[55] bg-[rgba(0,0,0,0.25)]" onClick={close} aria-hidden="true" />
      <aside
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        className="fixed inset-y-0 right-0 z-[60] flex w-full flex-col bg-white shadow-2xl focus:outline-none lg:w-[48vw]"
      >
        <div className="flex items-start gap-3 border-b border-[rgba(0,0,0,0.06)] px-5 pb-3 pt-4">
          {category !== null && <span className="mt-0.5"><CategoryIcon category={category} size={22} /></span>}
          <div className="min-w-0 flex-1 space-y-2">
            <h2 id={titleId} className="break-all text-base font-bold text-[rgba(0,0,0,0.88)]">{resourceId}</h2>
            <div className="flex flex-wrap items-center gap-2">
              <NeutralChip label={resourceType} />
              {region !== null && <NeutralChip label={region} />}
            </div>
          </div>
          {consoleUrl && (
            <a
              href={consoleUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex flex-shrink-0 items-center gap-1.5 rounded-lg border border-[rgba(0,0,0,0.12)] px-2.5 py-1.5 text-xs font-semibold text-[rgba(0,0,0,0.7)] transition-colors hover:bg-[rgba(0,0,0,0.04)]"
            >
              Go to AWS
              <Icon icon="mdi:open-in-new" width={12} className="text-[rgba(0,0,0,0.45)]" />
            </a>
          )}
          <button
            ref={closeButton}
            type="button"
            onClick={close}
            aria-label="Close resource"
            className="flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-md border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.5)] transition-colors hover:bg-[rgba(0,0,0,0.04)] hover:text-[rgba(0,0,0,0.85)]"
          >
            <Icon icon="mdi:close" width={14} />
          </button>
        </div>
        <div className="min-h-0 flex-1 space-y-4 overflow-y-auto px-5 py-4">
          <DetailCard title="Resource Details">
            <dl className="grid grid-cols-[9rem_1fr] items-center gap-y-1 text-xs">
              <dt className={DT}>Resource ID</dt>
              <dd className="flex min-w-0 items-center gap-1 font-mono text-[rgba(0,0,0,0.8)]">
                <span className="break-all">{resourceId}</span>
                {resource.resourceId && <CopyButton text={resource.resourceId} label="Copy resource ID" />}
              </dd>
              <dt className={DT}>Resource Type</dt>
              <dd className={DD}>{resourceType}</dd>
              <dt className={DT}>Category</dt>
              <dd className={DD}>{category ?? '-'}</dd>
              <dt className={DT}>Region</dt>
              <dd className={DD}>{region ?? '-'}</dd>
              <dt className={DT}>Account</dt>
              <dd className="min-w-0 break-words font-mono text-[rgba(0,0,0,0.8)]">{accountId ?? '-'}</dd>
              <dt className={DT}>Misconfigurations</dt>
              <dd className={DD}>{rows.length.toLocaleString('en-US')}</dd>
            </dl>
          </DetailCard>
          <section aria-labelledby={`${titleId}-misconfigurations`} className="space-y-2">
            <h3 id={`${titleId}-misconfigurations`} className="text-xs font-semibold text-[rgba(0,0,0,0.85)]">
              Misconfigurations on this resource{' '}
              <span className="ml-1 rounded bg-[rgba(0,0,0,0.06)] px-1.5 py-0.5 text-[10px] tabular-nums text-[rgba(0,0,0,0.55)]">
                {rows.length}
              </span>
            </h3>
            {rows.length === 0 ? (
              <p className="rounded-xl border border-[rgba(0,0,0,0.08)] px-4 py-6 text-center text-xs text-[rgba(0,0,0,0.45)]">
                No open misconfigurations on this resource.
              </p>
            ) : (
              <ul aria-label="Misconfigurations on this resource" className="space-y-2">
                {rows.map(row => (
                  <ResourceMisconfiguration
                    key={row.key}
                    row={row}
                    accountId={accountId}
                    open={openKeys.has(row.key)}
                    onToggle={() => toggle(row.key)}
                    onResolve={onResolve}
                    resolving={resolvingKeys.includes(findingKey(row.findings[0]))}
                  />
                ))}
              </ul>
            )}
          </section>
        </div>
      </aside>
    </>,
    document.body,
  );
};

export default ResourceDrawer;
