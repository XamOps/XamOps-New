import React from 'react';
import { Icon } from '@iconify/react';
import type { MisconfigurationDetail } from '../../../types/dspmMisconfigurations';
import { CodeBlock } from '../datastores/markdown/CodeBlock';
import { DetailCard } from '../datastores/drawer/RiskDetailPanel';
import { CopyButton } from '../datastores/ui/CopyButton';
import { ConsoleOnlyRegion, OpenChip } from './ConsoleParts';
import { OPEN_LABEL } from './misconfigurationModel';

/**
 * The drawer's Details section: the console's "Misconfiguration Details" card (Misconfiguration ID,
 * Policy ID, Category, Status), the description and the remediation as markdown, the CLI fix as a Bash
 * block and the vendor's "Learn More" link when it sent one (the backend keeps only absolute http and
 * https URLs). The route has no misconfiguration id (greyed, no value) and no category ("-"); every
 * finding it lists is open.
 */
const MisconfigurationDetails: React.FC<{ detail: MisconfigurationDetail }> = ({ detail }) => {
  const extras = detail.cli !== null || detail.learnMore !== null ? (
    <>
      {detail.cli !== null && <CodeBlock language="Bash" code={detail.cli} />}
      {detail.learnMore !== null && (
        <a
          href={detail.learnMore}
          target="_blank"
          rel="noopener noreferrer"
          className="inline-flex items-center gap-1 text-xs font-semibold text-sky-700 hover:underline"
        >
          Learn More
          <Icon icon="mdi:open-in-new" width={12} />
        </a>
      )}
    </>
  ) : null;

  return (
    <div className="space-y-3">
      <DetailCard title="Misconfiguration Details" markdown={null}>
        <dl className="grid grid-cols-[9rem_1fr] items-center gap-y-1.5 text-xs">
          <dt className="text-[rgba(0,0,0,0.5)]">Misconfiguration ID</dt>
          <dd className="min-w-0">
            <ConsoleOnlyRegion label="Misconfiguration ID" inline>
              <span className="px-0.5 text-[rgba(0,0,0,0.8)]">-</span>
            </ConsoleOnlyRegion>
          </dd>
          <dt className="text-[rgba(0,0,0,0.5)]">Policy ID</dt>
          <dd className="flex min-w-0 items-center gap-1 font-mono text-[rgba(0,0,0,0.8)]">
            <span className="truncate">{detail.policyId}</span>
            <CopyButton text={detail.policyId} label="Copy policy ID" />
          </dd>
          <dt className="text-[rgba(0,0,0,0.5)]">Category</dt>
          <dd className="text-[rgba(0,0,0,0.8)]">-</dd>
          <dt className="text-[rgba(0,0,0,0.5)]">Status</dt>
          <dd><OpenChip label={OPEN_LABEL} /></dd>
        </dl>
      </DetailCard>
      <DetailCard title="Description" markdown={detail.description} />
      <DetailCard title="Remediation" markdown={detail.remediation}>{extras}</DetailCard>
    </div>
  );
};

export default MisconfigurationDetails;
