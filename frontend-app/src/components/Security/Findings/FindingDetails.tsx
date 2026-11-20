import React, { useId } from 'react';
import { CopyButton } from '../../Dspm/datastores/ui/CopyButton';
import { guideFor, liveLearnMore } from './checkGuides';
import Remediation from './Remediation';
import type { SecurityMisconfiguration } from './findingsModel';

/**
 * A card of a drawer section: its heading and plain text, any extra content, or "-" when there is
 * neither. The XamLens card renders Matters' markdown; findings here are plain text, so they render
 * as written.
 */
export const DetailCard: React.FC<{ title: string; text?: string | null; children?: React.ReactNode }> = ({ title, text, children }) => {
  const id = useId();
  const hasText = Boolean(text?.trim());
  return (
    <section aria-labelledby={id} className="space-y-2 rounded-xl border border-[rgba(0,0,0,0.08)] p-4">
      <h4 id={id} className="text-xs font-semibold text-[rgba(0,0,0,0.85)]">{title}</h4>
      {hasText && (
        <p className="whitespace-pre-line break-words text-xs leading-relaxed text-[rgba(0,0,0,0.75)]">{text}</p>
      )}
      {children}
      {!hasText && !children && <p className="text-xs text-[rgba(0,0,0,0.4)]">-</p>}
    </section>
  );
};

const DT = 'text-[rgba(0,0,0,0.5)]';
const DD = 'min-w-0 break-words text-[rgba(0,0,0,0.8)]';

interface Props {
  row: SecurityMisconfiguration;
  accountId: string | null;
}

/**
 * The drawer's Details section: control, framework, category and resource type, then what the finding
 * means and how to fix it.
 *
 * Live findings carry their own description and remediation, plus a link to AWS's page for a Security
 * Hub control or GuardDuty. The checks XamOps runs itself send one sentence and no remediation, so theirs
 * come from `checkGuides`. The CLI names the affected resource when there is one; with several, it keeps
 * placeholders and says where to get each one's command.
 */
const FindingDetails: React.FC<Props> = ({ row, accountId }) => {
  const single = row.findings.length === 1 ? row.findings[0] : null;
  const guide = guideFor(row.title, single
    ? { resourceId: single.resourceId, region: single.region, accountId }
    : { region: row.regions.length === 1 ? row.regions[0] : null, accountId });
  const hint = guide?.cli && guide.cliPerResource && row.findings.length > 1
    ? `The command has placeholders: open one of the ${row.findings.length} affected resources to get it with that resource's values.`
    : null;
  return (
    <div className="space-y-3">
      <DetailCard title="Misconfiguration Details">
        <dl className="grid grid-cols-[8rem_1fr] items-center gap-y-1 text-xs">
          <dt className={DT}>Control ID</dt>
          <dd className="flex min-w-0 items-center gap-1 font-mono text-[rgba(0,0,0,0.8)]">
            <span className="truncate">{row.controlId ?? '-'}</span>
            {row.controlId !== null && <CopyButton text={row.controlId} label="Copy control ID" />}
          </dd>
          <dt className={DT}>Framework</dt>
          <dd className={DD}>{row.framework ?? '-'}</dd>
          <dt className={DT}>Category</dt>
          <dd className={DD}>{row.category ?? '-'}</dd>
          <dt className={DT}>Resource Type</dt>
          <dd className={DD}>{guide?.resourceType ?? row.category ?? '-'}</dd>
        </dl>
      </DetailCard>
      <DetailCard title="Description" text={row.description ?? guide?.description ?? row.title} />
      <DetailCard title="Remediation">
        <Remediation
          text={row.remediation}
          guide={guide}
          learnMore={guide?.learnMore ?? liveLearnMore(row.framework, row.controlId)}
          hint={hint}
        />
      </DetailCard>
    </div>
  );
};

export default FindingDetails;
