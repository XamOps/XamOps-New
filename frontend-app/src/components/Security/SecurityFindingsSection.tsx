import React from 'react';
import { Icon } from '@iconify/react';
import type { SecurityFinding, SeverityLevel } from '../../types/security';

interface SecurityFindingsSectionProps {
  severity: SeverityLevel;
  findings: SecurityFinding[];
  isOpen: boolean;
  onToggle: () => void;
}

const SEVERITY_CONFIG: Record<SeverityLevel, { header: string; badge: string; icon: string; dot: string }> = {
  Critical: { header: 'bg-red-50 text-red-800',    badge: 'bg-red-100 text-red-700 border-red-200',    icon: 'mdi:skull-crossbones-outline', dot: 'bg-red-500' },
  High:     { header: 'bg-orange-50 text-orange-800', badge: 'bg-orange-100 text-orange-700 border-orange-200', icon: 'mdi:alert-triangle', dot: 'bg-orange-500' },
  Medium:   { header: 'bg-yellow-50 text-yellow-800', badge: 'bg-yellow-100 text-yellow-700 border-yellow-200', icon: 'mdi:alert-circle-outline', dot: 'bg-yellow-500' },
  Low:      { header: 'bg-blue-50 text-blue-800',   badge: 'bg-blue-100 text-blue-700 border-blue-200',   icon: 'mdi:information-outline', dot: 'bg-blue-400' },
};

const SecurityFindingsSection: React.FC<SecurityFindingsSectionProps> = ({ severity, findings, isOpen, onToggle }) => {
  if (findings.length === 0) return null;

  const cfg = SEVERITY_CONFIG[severity];

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
      <button
        onClick={onToggle}
        className={`w-full flex items-center justify-between px-5 py-3.5 ${cfg.header} transition-colors`}
      >
        <div className="flex items-center gap-3">
          <span className={`w-2 h-2 rounded-full ${cfg.dot}`} />
          <Icon icon={cfg.icon} width={15} />
          <span className="text-sm font-bold">{severity} Severity Findings</span>
        </div>
        <div className="flex items-center gap-3">
          <span className={`text-xs font-bold px-2 py-0.5 rounded-full border ${cfg.badge}`}>{findings.length}</span>
          <Icon icon="mdi:chevron-down" width={16} className={`transition-transform duration-200 ${isOpen ? 'rotate-180' : ''}`} />
        </div>
      </button>

      {isOpen && (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)]">
              <tr>
                <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-[rgba(0,0,0,0.4)]">Category</th>
                <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-[rgba(0,0,0,0.4)]">Description</th>
                <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-[rgba(0,0,0,0.4)]">Resource</th>
                <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-[rgba(0,0,0,0.4)]">Source & Control</th>
              </tr>
            </thead>
            <tbody>
              {findings.map((finding, index) => (
                <tr key={`${finding.resourceId}-${index}`} className="border-t border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.025)] transition-colors">
                  <td className="px-4 py-3">
                    <span className="text-xs font-medium text-[rgba(0,0,0,0.7)] bg-[rgba(0,0,0,0.04)] px-2 py-0.5 rounded">{finding.category}</span>
                  </td>
                  <td className="px-4 py-3">
                    <span className="text-xs text-[rgba(0,0,0,0.7)] line-clamp-2">{finding.description}</span>
                  </td>
                  <td className="px-4 py-3">
                    <div className="text-xs font-medium text-[rgba(0,0,0,0.88)] truncate max-w-[160px]">{finding.resourceId}</div>
                    <div className="text-[11px] text-[rgba(0,0,0,0.4)]">{finding.region}</div>
                  </td>
                  <td className="px-4 py-3">
                    <span className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-semibold ${
                      finding.complianceFramework?.includes('SOC2')
                        ? 'bg-indigo-50 text-indigo-700 border border-indigo-200'
                        : finding.complianceFramework?.includes('Prowler')
                        ? 'bg-purple-50 text-purple-700 border border-purple-200'
                        : 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.6)]'
                    }`}>
                      {(finding.complianceFramework || 'Unknown') + (finding.controlId ? ` (${finding.controlId})` : '')}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};

export default SecurityFindingsSection;
