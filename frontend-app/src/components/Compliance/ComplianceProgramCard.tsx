import React from 'react';
import { Icon } from '@iconify/react';
import type { ComplianceCounts, ComplianceProgramHealth } from '../../types/compliance';
import { CARD, EYEBROW } from './complianceTokens';

interface Props {
  program: ComplianceProgramHealth;
  counts: ComplianceCounts;
  onShowUnowned: () => void;
  onShowWaived: () => void;
}

/**
 * How the compliance programme is doing, as distinct from how the cloud config is doing.
 *
 * This is the half of compliance the page had no answer for at all. A 90% score with
 * forty unowned failing controls and six lapsed waivers is a worse position than 80% with
 * everything owned and reviewed, and until now nothing on the page could say so. Each row
 * is a specific unanswered question with a number attached, and the ones that are
 * actionable filter the list.
 */
const ComplianceProgramCard: React.FC<Props> = ({
  program, counts, onShowUnowned, onShowWaived,
}) => {
  const rows: Array<{
    icon: string;
    label: string;
    value: number;
    tone: 'bad' | 'warn' | 'neutral' | 'good';
    hint: string;
    onClick?: () => void;
  }> = [
    {
      icon: 'mdi:account-question-outline',
      label: 'Failing controls with no owner',
      value: program.failingControlsWithoutOwner,
      tone: program.failingControlsWithoutOwner > 0 ? 'bad' : 'good',
      hint: 'Nobody is accountable for fixing these.',
      onClick: program.failingControlsWithoutOwner > 0 ? onShowUnowned : undefined,
    },
    {
      icon: 'mdi:file-document-alert-outline',
      label: 'Waivers expiring within 30 days',
      value: program.exceptionsExpiringSoon,
      tone: program.exceptionsExpiringSoon > 0 ? 'warn' : 'good',
      hint: 'These need re-reviewing before they lapse.',
      onClick: counts.waivedControls > 0 ? onShowWaived : undefined,
    },
    {
      icon: 'mdi:calendar-remove-outline',
      label: 'Lapsed waivers',
      value: program.lapsedExceptions,
      tone: program.lapsedExceptions > 0 ? 'bad' : 'good',
      hint: 'Past expiry, so the underlying control is failing again.',
    },
    {
      icon: 'mdi:clock-alert-outline',
      label: 'Exception requests awaiting approval',
      value: program.pendingExceptions,
      tone: program.pendingExceptions > 0 ? 'warn' : 'neutral',
      hint: 'Requested but not yet decided, so not suppressing anything.',
    },
    {
      icon: 'mdi:wrench-clock',
      label: 'Remediation in progress',
      value: program.remediationOpen + program.remediationInProgress,
      tone: 'neutral',
      hint: 'Open and in-progress fixes.',
    },
    {
      icon: 'mdi:shield-search',
      label: 'Fixes awaiting scan verification',
      value: program.remediationAwaitingVerification,
      tone: program.remediationAwaitingVerification > 0 ? 'warn' : 'neutral',
      hint: 'Marked resolved, but no scan has yet observed the control passing.',
    },
    {
      icon: 'mdi:calendar-alert',
      label: 'Overdue remediation',
      value: program.remediationOverdue,
      tone: program.remediationOverdue > 0 ? 'bad' : 'good',
      hint: 'Past the agreed due date.',
    },
  ];

  return (
    <section className={CARD}>
      <header className="flex items-center justify-between gap-3 px-4 py-2.5 border-b border-[#e5e7eb]">
        <span className={EYEBROW}>Programme health</span>
        <span className="text-[10.5px] font-medium text-[rgba(0,0,0,0.35)] tabular-nums">
          {program.completedScans} scan{program.completedScans === 1 ? '' : 's'} recorded
        </span>
      </header>

      <ul className="divide-y divide-[#e5e7eb]">
        {rows.map((row) => {
          const interactive = Boolean(row.onClick);
          const tone = row.value === 0
            ? 'text-[rgba(0,0,0,0.3)]'
            : row.tone === 'bad'
              ? 'text-red-600'
              : row.tone === 'warn'
                ? 'text-amber-600'
                : row.tone === 'good'
                  ? 'text-emerald-600'
                  : 'text-[rgba(0,0,0,0.88)]';

          return (
            <li key={row.label}>
              <div
                role={interactive ? 'button' : undefined}
                tabIndex={interactive ? 0 : undefined}
                onClick={row.onClick}
                onKeyDown={(e) => {
                  if (!interactive) return;
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    row.onClick?.();
                  }
                }}
                title={row.hint}
                className={`flex items-center gap-3 px-4 py-2.5 transition-colors ${
                  interactive
                    ? 'cursor-pointer hover:bg-[#fafafa] focus:outline-none focus-visible:bg-[#f5f5f5]'
                    : ''
                }`}
              >
                <Icon icon={row.icon} width={15} className="text-[rgba(0,0,0,0.3)] shrink-0" />
                <div className="min-w-0 flex-1">
                  <p className="text-[12px] font-medium text-[rgba(0,0,0,0.78)] truncate leading-snug">
                    {row.label}
                  </p>
                  <p className="text-[10.5px] text-[rgba(0,0,0,0.35)] truncate leading-snug">
                    {row.hint}
                  </p>
                </div>
                <span className={`text-[16px] font-semibold tabular-nums ${tone}`}>
                  {row.value}
                </span>
                {interactive && (
                  <Icon
                    icon="mdi:chevron-right"
                    width={14}
                    className="text-[rgba(0,0,0,0.18)] shrink-0"
                  />
                )}
              </div>
            </li>
          );
        })}
      </ul>
    </section>
  );
};

export default ComplianceProgramCard;
