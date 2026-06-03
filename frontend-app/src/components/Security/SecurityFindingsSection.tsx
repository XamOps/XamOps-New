import React from 'react';
import { Icon } from '@iconify/react';
import type { SecurityFinding, SeverityLevel } from '../../types/security';

interface SecurityFindingsSectionProps {
  severity: SeverityLevel;
  findings: SecurityFinding[];
  isOpen: boolean;
  onToggle: () => void;
}

const SEVERITY_CONFIG: Record<SeverityLevel, {
  iconBg: string;
  iconColor: string;
  icon: string;
  badgeBg: string;
  badgeText: string;
}> = {
  Critical: { iconBg: 'bg-red-500',    iconColor: 'text-white',     icon: 'mdi:alert-circle',  badgeBg: 'bg-red-500',    badgeText: 'text-white' },
  High:     { iconBg: 'bg-orange-500', iconColor: 'text-white',     icon: 'mdi:alert',         badgeBg: 'bg-orange-500', badgeText: 'text-white' },
  Medium:   { iconBg: 'bg-yellow-400', iconColor: 'text-gray-900',  icon: 'mdi:minus-circle',  badgeBg: 'bg-yellow-400', badgeText: 'text-gray-900' },
  Low:      { iconBg: 'bg-blue-500',   iconColor: 'text-white',     icon: 'mdi:information',   badgeBg: 'bg-blue-500',   badgeText: 'text-white' },
};

const SecurityFindingsSection: React.FC<SecurityFindingsSectionProps> = ({ severity, findings, isOpen, onToggle }) => {
  if (findings.length === 0) return null;

  const cfg = SEVERITY_CONFIG[severity];

  return (
    <div className="rounded-xl overflow-hidden shadow-sm border border-[rgba(255,255,255,0.06)]">
      {/* Header */}
      <button
        onClick={onToggle}
        className="w-full flex items-center justify-between px-5 py-3.5 bg-[#111111] hover:bg-[#1a1a1a] transition-colors"
      >
        <div className="flex items-center gap-3">
          <div className={`w-7 h-7 rounded-full flex items-center justify-center ${cfg.iconBg}`}>
            <Icon icon={cfg.icon} width={16} className={cfg.iconColor} />
          </div>
          <span className="text-sm font-bold text-white">{severity} Severity Findings</span>
        </div>
        <div className="flex items-center gap-3">
          <span className={`text-xs font-bold px-2.5 py-0.5 rounded-full ${cfg.badgeBg} ${cfg.badgeText}`}>
            {findings.length}
          </span>
          <Icon
            icon="mdi:chevron-down"
            width={16}
            className={`text-white/60 transition-transform duration-300 ease-in-out ${isOpen ? 'rotate-180' : 'rotate-0'}`}
          />
        </div>
      </button>

      {/* Animated body using grid-template-rows trick */}
      <div
        style={{
          display: 'grid',
          gridTemplateRows: isOpen ? '1fr' : '0fr',
          transition: 'grid-template-rows 320ms cubic-bezier(0.4, 0, 0.2, 1)',
        }}
      >
        <div style={{ overflow: 'hidden', minHeight: 0 }}>
          <div
            style={{
              opacity: isOpen ? 1 : 0,
              transform: isOpen ? 'translateY(0)' : 'translateY(-6px)',
              transition: 'opacity 280ms ease, transform 280ms ease',
            }}
            className="overflow-x-auto bg-white border-t border-[rgba(0,0,0,0.08)]"
          >
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
                  <tr
                    key={`${finding.resourceId}-${index}`}
                    className="border-t border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.025)] transition-colors"
                  >
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
        </div>
      </div>
    </div>
  );
};

export default SecurityFindingsSection;
