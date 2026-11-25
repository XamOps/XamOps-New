import React from 'react';
import type { RegionCoverage } from '../../../api/autospottingApi';
import { Icon } from '@iconify/react';
import { CARD, CARD_TITLE, MUTED } from './theme';

const LABEL: Record<string, string> = { COVERED: 'Covered', MISSING: 'Missing', SKIPPED: 'Skipped', UNKNOWN: 'Unknown' };

/**
 * Event coverage per region on the AWS account view (design-aws.md §9.4, FR-EVT-02, PO-22). Only a covered
 * region lets a group convert; any other status shows "Update stack" as the fix. Hidden for a read-only
 * connection and before the first check.
 */
const RegionCoverageList: React.FC<{ coverage: RegionCoverage | undefined }> = ({ coverage }) => {
  if (!coverage || !coverage.applicable || coverage.regions.length === 0) return null;
  const gaps = coverage.regions.filter(r => r.status !== 'COVERED').length;
  return (
    <section aria-label="Event coverage" className={`${CARD} px-5 py-4 text-[13px]`}>
      <p className={CARD_TITLE}>Event coverage</p>
      <ul className="mt-2 flex flex-wrap gap-2">
        {coverage.regions.map(r => (
          <li
            key={r.region}
            className={`inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-[11px] font-semibold ${
              r.status === 'COVERED'
                ? 'border-emerald-200 bg-emerald-50 text-emerald-700'
                : 'border-amber-200 bg-amber-50 text-amber-800'
            }`}
          >
            <Icon icon={r.status === 'COVERED' ? 'mdi:check-circle' : 'mdi:alert-outline'} width={12} aria-hidden="true" />
            {`${r.region}: ${LABEL[r.status] ?? r.status}`}
          </li>
        ))}
      </ul>
      {gaps > 0 && (
        <p className={`mt-2 ${MUTED}`}>Groups in an uncovered region can't convert. Update stack to cover them.</p>
      )}
    </section>
  );
};

export default RegionCoverageList;
