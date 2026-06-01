import React, { useState } from 'react';
import { Icon } from '@iconify/react';
import type { AzureSecurityAssessment } from '../../types/azureSecurity';

interface AzureSecurityAssessmentsSectionProps {
  assessments: AzureSecurityAssessment[];
  isLoading: boolean;
}

type SeverityLevel = 'High' | 'Medium' | 'Low';

const SEVERITY_CONFIG: Record<SeverityLevel, { label: string; bg: string; text: string; border: string; dot: string }> = {
  High:   { label: 'High',   bg: 'bg-red-50',   text: 'text-red-700',   border: 'border-red-200',   dot: 'bg-red-500' },
  Medium: { label: 'Medium', bg: 'bg-amber-50',  text: 'text-amber-700', border: 'border-amber-200', dot: 'bg-amber-500' },
  Low:    { label: 'Low',    bg: 'bg-blue-50',   text: 'text-blue-700',  border: 'border-blue-200',  dot: 'bg-blue-400' },
};

const SeverityBadge: React.FC<{ severity: string }> = ({ severity }) => {
  const config = SEVERITY_CONFIG[severity as SeverityLevel] ?? {
    label: severity, bg: 'bg-gray-50', text: 'text-gray-600', border: 'border-gray-200', dot: 'bg-gray-400',
  };
  return (
    <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-semibold ${config.bg} ${config.text} border ${config.border}`}>
      <span className={`w-1.5 h-1.5 rounded-full ${config.dot}`} />
      {config.label}
    </span>
  );
};

const SkeletonRow = () => (
  <tr className="border-b border-gray-100 animate-pulse">
    <td className="px-4 py-4"><div className="h-3 bg-gray-200 rounded w-32" /></td>
    <td className="px-4 py-4"><div className="h-3 bg-gray-200 rounded w-20" /></td>
    <td className="px-4 py-4"><div className="h-3 bg-gray-200 rounded w-48" /></td>
    <td className="px-4 py-4"><div className="h-5 bg-gray-200 rounded w-20" /></td>
    <td className="px-4 py-4"><div className="h-5 bg-gray-200 rounded w-14" /></td>
  </tr>
);

interface SeverityGroupProps {
  severity: SeverityLevel;
  items: AzureSecurityAssessment[];
}

const SeverityGroup: React.FC<SeverityGroupProps> = ({ severity, items }) => {
  const [open, setOpen] = useState(true);
  const config = SEVERITY_CONFIG[severity];
  if (items.length === 0) return null;

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
      <button
        onClick={() => setOpen(o => !o)}
        className={`w-full flex items-center justify-between px-5 py-3 ${config.bg} hover:opacity-90 transition-opacity`}
      >
        <div className="flex items-center gap-3">
          <span className={`w-2 h-2 rounded-full ${config.dot}`} />
          <span className={`text-sm font-bold ${config.text}`}>{config.label} Severity</span>
          <span className={`text-xs font-semibold px-2 py-0.5 rounded-full ${config.bg} ${config.text} border ${config.border}`}>
            {items.length}
          </span>
        </div>
        <Icon icon={open ? 'mdi:chevron-up' : 'mdi:chevron-down'} width={16} className={config.text} />
      </button>

      {open && (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 border-b border-gray-100">
              <tr>
                <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Resource</th>
                <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Category</th>
                <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Recommendation</th>
                <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-gray-500">SOC2 Control</th>
                <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Severity</th>
              </tr>
            </thead>
            <tbody>
              {items.map((item, idx) => (
                <tr key={item.assessmentId || idx} className="border-t border-gray-100 hover:bg-gray-50 transition-colors">
                  <td className="px-4 py-3">
                    <span className="text-gray-800 font-medium text-xs truncate max-w-[160px] block" title={item.resourceName}>
                      {item.resourceName || '—'}
                    </span>
                  </td>
                  <td className="px-4 py-3">
                    <span className="text-xs text-gray-500 bg-gray-100 px-2 py-0.5 rounded">{item.category || '—'}</span>
                  </td>
                  <td className="px-4 py-3">
                    <span className="text-gray-700 text-xs line-clamp-2" title={item.displayName}>{item.displayName || '—'}</span>
                  </td>
                  <td className="px-4 py-3">
                    {item.complianceControl ? (
                      <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-semibold bg-indigo-50 text-indigo-700 border border-indigo-200">
                        {item.complianceControl}
                      </span>
                    ) : (
                      <span className="text-xs text-gray-400">—</span>
                    )}
                  </td>
                  <td className="px-4 py-3"><SeverityBadge severity={item.severity} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};

const AzureSecurityAssessmentsSection: React.FC<AzureSecurityAssessmentsSectionProps> = ({
  assessments,
  isLoading,
}) => {
  if (isLoading) {
    return (
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
        <div className="px-5 py-3 border-b border-gray-100">
          <div className="h-4 bg-gray-200 rounded w-32 animate-pulse" />
        </div>
        <table className="w-full text-sm">
          <tbody><SkeletonRow /><SkeletonRow /><SkeletonRow /></tbody>
        </table>
      </div>
    );
  }

  if (assessments.length === 0) {
    return (
      <div className="text-center py-16 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm">
        <div className="w-14 h-14 bg-emerald-50 border border-emerald-100 rounded-xl flex items-center justify-center mx-auto mb-3">
          <Icon icon="mdi:check-circle-outline" width={28} color="#10b981" />
        </div>
        <h3 className="text-base font-bold text-gray-700 mb-1">No Security Findings</h3>
        <p className="text-sm text-gray-500">All assessments are passing or not applicable.</p>
      </div>
    );
  }

  return (
    <div className="space-y-3">
      <SeverityGroup severity="High"   items={assessments.filter(a => a.severity === 'High')} />
      <SeverityGroup severity="Medium" items={assessments.filter(a => a.severity === 'Medium')} />
      <SeverityGroup severity="Low"    items={assessments.filter(a => a.severity === 'Low')} />
    </div>
  );
};

export default AzureSecurityAssessmentsSection;
