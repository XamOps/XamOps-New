import React from 'react';
import { Icon } from '@iconify/react';
import { BTN_PRIMARY, CARD } from './complianceTokens';

/**
 * Shape-matched skeleton. The blocks mirror the real layout, so the page does not jump
 * when data lands — a generic spinner would reflow everything on arrival.
 */
export const ComplianceSkeleton: React.FC = () => (
  <div className="animate-pulse space-y-3">
    <div className="h-[200px] bg-[rgba(0,0,0,0.04)] rounded-xl" />
    <div className="h-[132px] bg-[rgba(0,0,0,0.04)] rounded-xl" />
    <div className="grid grid-cols-1 lg:grid-cols-3 gap-3">
      <div className="h-[248px] bg-[rgba(0,0,0,0.04)] rounded-xl" />
      <div className="lg:col-span-2 h-[248px] bg-[rgba(0,0,0,0.04)] rounded-xl" />
    </div>
    <div className="h-12 bg-[rgba(0,0,0,0.04)] rounded-xl" />
    <div className="h-[420px] bg-[rgba(0,0,0,0.04)] rounded-xl" />
  </div>
);

interface EmptyProps {
  icon: string;
  title: string;
  body: string;
  action?: { label: string; icon: string; onClick: () => void; disabled?: boolean };
}

export const ComplianceEmptyState: React.FC<EmptyProps> = ({ icon, title, body, action }) => (
  <div className={CARD}>
    <div className="flex flex-col items-center justify-center py-16 text-center px-6">
      <div className="w-14 h-14 bg-[rgba(0,0,0,0.04)] rounded-xl flex items-center justify-center mb-3">
        <Icon icon={icon} width={26} className="text-[rgba(0,0,0,0.35)]" />
      </div>
      <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.78)] mb-1">{title}</p>
      <p className="text-[12px] text-[rgba(0,0,0,0.45)] mb-4 max-w-[52ch] leading-relaxed">
        {body}
      </p>
      {action && (
        <button
          type="button"
          onClick={action.onClick}
          disabled={action.disabled}
          className={BTN_PRIMARY}
        >
          <Icon icon={action.icon} width={14} className={action.disabled ? 'animate-spin' : ''} />
          {action.label}
        </button>
      )}
    </div>
  </div>
);

/**
 * A failed scan, shown as its own state rather than as an empty one.
 *
 * The distinction matters: "we could not read your account" and "your account is clean"
 * look identical if both render as an empty list, and only one of them is the customer's
 * problem to act on.
 */
export const ComplianceScanFailed: React.FC<{
  error: string | null;
  scannedAt: string | null;
  onRetry: () => void;
  retrying: boolean;
}> = ({ error, scannedAt, onRetry, retrying }) => (
  <div className="bg-white border border-red-200 border-l-[3px] border-l-red-500 rounded-xl p-5">
    <div className="flex items-start gap-3">
      <Icon icon="mdi:alert-circle-outline" width={20} className="text-red-500 mt-[1px] shrink-0" />
      <div className="min-w-0 flex-1">
        <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)]">
          The last compliance scan did not complete
        </p>
        <p className="text-[12.5px] text-[rgba(0,0,0,0.55)] leading-relaxed mt-1.5 max-w-[70ch]">
          No results were recorded, so there is nothing to score. This is a scan problem, not
          a finding about the account — usually expired or insufficient credentials.
        </p>
        {error && (
          <div className="mt-3">
            <span className="text-[10.5px] font-semibold uppercase tracking-widest text-[rgba(0,0,0,0.35)]">
              Scanner said
            </span>
            <p className="mt-1 px-3 py-2 rounded-lg bg-[#f9fafb] border border-[#e5e7eb] text-[11.5px] font-mono text-[rgba(0,0,0,0.65)] break-words leading-relaxed">
              {error}
            </p>
          </div>
        )}
        {scannedAt && (
          <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-2">
            Attempted {new Date(scannedAt).toLocaleString()}
          </p>
        )}
        <button
          type="button"
          onClick={onRetry}
          disabled={retrying}
          className={`${BTN_PRIMARY} mt-3.5`}
        >
          <Icon icon="mdi:refresh" width={14} className={retrying ? 'animate-spin' : ''} />
          {retrying ? 'Scan running…' : 'Run the scan again'}
        </button>
      </div>
    </div>
  </div>
);

export const ComplianceErrorState: React.FC<{ message: string; onRetry: () => void }> = ({
  message, onRetry,
}) => (
  <div className={CARD}>
    <div className="flex flex-col items-center justify-center py-16 text-center px-6">
      <div className="w-14 h-14 bg-red-50 border border-red-100 rounded-xl flex items-center justify-center mb-3">
        <Icon icon="mdi:cloud-alert-outline" width={26} className="text-red-500" />
      </div>
      <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.78)] mb-1">
        Could not load the compliance report
      </p>
      <p className="text-[12px] text-[rgba(0,0,0,0.45)] mb-4 max-w-[52ch] leading-relaxed">
        {message}
      </p>
      <button type="button" onClick={onRetry} className={BTN_PRIMARY}>
        <Icon icon="mdi:refresh" width={14} />
        Try again
      </button>
    </div>
  </div>
);
