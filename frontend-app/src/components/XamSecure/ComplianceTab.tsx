import React from 'react';
import type { ComplianceFramework } from '../../types/xamsecure';

interface Props {
  data: ComplianceFramework[] | null;
  isLoading: boolean;
}

const ComplianceTab: React.FC<Props> = ({ data, isLoading }) => {
  const frameworks = data ?? [];
  if (isLoading && frameworks.length === 0) {
    return <div className="text-center py-16 text-sm text-gray-400">Loading compliance frameworks...</div>;
  }
  if (frameworks.length === 0) {
    return (
      <div className="text-center py-16 bg-white border border-gray-200 rounded-xl shadow-sm text-sm text-gray-400">
        No compliance framework data for the selected account.
      </div>
    );
  }
  return (
    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
      {frameworks.map((f) => {
        const total = f.failCount + f.passCount;
        const passPct = total === 0 ? 0 : Math.round((f.passCount / total) * 100);
        return (
          <div key={f.framework} className="bg-white border border-gray-200 rounded-xl shadow-sm p-5">
            <div className="flex items-start justify-between">
              <p className="text-sm font-bold text-gray-900 pr-3">{f.framework}</p>
              <p className="text-lg font-bold text-gray-900">{passPct}%</p>
            </div>
            <div className="mt-3 h-1.5 bg-gray-100 rounded-full overflow-hidden">
              <div className="h-full bg-gray-900" style={{ width: `${passPct}%` }} />
            </div>
            <div className="flex gap-4 mt-3 text-xs text-gray-500">
              <span><span className="font-bold text-gray-900">{f.passCount}</span> passing</span>
              <span><span className="font-bold text-gray-900">{f.failCount}</span> failing</span>
            </div>
          </div>
        );
      })}
    </div>
  );
};

export default ComplianceTab;
