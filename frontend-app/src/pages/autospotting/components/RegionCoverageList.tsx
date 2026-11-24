import React from 'react';
import type { RegionCoverage } from '../../../api/autospottingApi';

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
    <section aria-label="Event coverage" className="rounded border border-gray-300 bg-white px-4 py-3 text-sm text-black">
      <p className="font-semibold">Event coverage</p>
      <ul className="mt-1 flex flex-wrap gap-x-4 gap-y-1">
        {coverage.regions.map(r => (
          <li key={r.region} className={r.status === 'COVERED' ? 'text-gray-600' : 'font-semibold text-black'}>
            {`${r.region}: ${LABEL[r.status] ?? r.status}`}
          </li>
        ))}
      </ul>
      {gaps > 0 && (
        <p className="mt-1 text-gray-600">Groups in an uncovered region can't convert. Update stack to cover them.</p>
      )}
    </section>
  );
};

export default RegionCoverageList;
