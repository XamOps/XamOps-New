import React from 'react';
import { Icon } from '@iconify/react';
import { BTN_PRIMARY, CARD } from './warTokens';

/**
 * Skeleton shown while a scan is in flight.
 *
 * Shape-matched to the real layout — anchor card, path grid, then list rows — so the
 * page does not visibly reflow when data lands.
 */
export const WarLoadingState: React.FC<{ isRefreshing?: boolean }> = ({ isRefreshing }) => (
  <div className="space-y-3">
    <div className={`${CARD} p-6`}>
      <div className="flex items-center gap-2.5 mb-5">
        <Icon icon="mdi:radar" width={16} className="text-[rgba(0,0,0,0.45)] animate-spin" />
        <div>
          <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.78)]">
            {isRefreshing ? 'Re-scanning your infrastructure' : 'Reviewing your attack surface'}
          </p>
          <p className="text-[12px] text-[rgba(0,0,0,0.45)] mt-0.5">
            Evaluating 86 controls across every active region. A first scan can take up to
            two minutes.
          </p>
        </div>
      </div>

      <div className="flex flex-col lg:flex-row lg:items-center gap-7 animate-pulse">
        <div className="w-[116px] h-[116px] rounded-full bg-[rgba(0,0,0,0.06)] shrink-0" />
        <div className="flex-1 grid grid-cols-2 sm:grid-cols-3 xl:grid-cols-5 gap-2.5">
          {[0, 1, 2, 3, 4].map((i) => (
            <div key={i} className="border border-[#e5e7eb] rounded-lg p-2.5 space-y-2">
              <div className="h-2 w-[60%] bg-[rgba(0,0,0,0.06)] rounded" />
              <div className="h-5 w-[35%] bg-[rgba(0,0,0,0.06)] rounded" />
              <div className="h-2 w-[70%] bg-[rgba(0,0,0,0.06)] rounded" />
            </div>
          ))}
        </div>
      </div>
    </div>

    <div className={`${CARD} p-4 animate-pulse`}>
      <div className="h-2.5 w-[110px] bg-[rgba(0,0,0,0.06)] rounded mb-3" />
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-2.5">
        {[0, 1, 2, 3, 4, 5, 6, 7].map((i) => (
          <div key={i} className="border border-[rgba(0,0,0,0.1)] rounded-lg p-3 space-y-2">
            <div className="h-2.5 w-[60%] bg-[rgba(0,0,0,0.06)] rounded" />
            <div className="h-2 w-[85%] bg-[rgba(0,0,0,0.05)] rounded" />
            <div className="h-5 w-[45%] bg-[rgba(0,0,0,0.06)] rounded" />
            <div className="h-1 w-full bg-[rgba(0,0,0,0.05)] rounded-full" />
          </div>
        ))}
      </div>
    </div>

    <div
      className={`${CARD} overflow-hidden divide-y divide-[#e5e7eb] animate-pulse`}
    >
      {[0, 1, 2, 3, 4, 5].map((i) => (
        <div key={i} className="flex items-center gap-4 px-4 py-3">
          <div className="flex-1 space-y-1.5">
            <div className="h-2 w-[12%] bg-[rgba(0,0,0,0.06)] rounded" />
            <div className="h-3.5 w-[45%] bg-[rgba(0,0,0,0.06)] rounded" />
          </div>
          <div className="h-4 w-[110px] bg-[rgba(0,0,0,0.06)] rounded" />
          <div className="h-4 w-[70px] bg-[rgba(0,0,0,0.06)] rounded" />
          <div className="h-5 w-[90px] bg-[rgba(0,0,0,0.06)] rounded-full" />
        </div>
      ))}
    </div>
  </div>
);

/** House empty state: icon in a rounded square, no card, black primary action. */
const EmptyShell: React.FC<{
  icon: string;
  iconClass?: string;
  frameClass?: string;
  title: string;
  body: React.ReactNode;
  action?: React.ReactNode;
}> = ({ icon, iconClass, frameClass, title, body, action }) => (
  <div className="flex flex-col items-center justify-center py-16">
    <div
      className={`w-14 h-14 rounded-xl flex items-center justify-center mx-auto mb-4 border ${
        frameClass ?? 'bg-[rgba(0,0,0,0.04)] border-[#e5e7eb]'
      }`}
    >
      <Icon icon={icon} width={28} className={iconClass ?? 'text-[rgba(0,0,0,0.45)]'} />
    </div>
    <h3 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">{title}</h3>
    <p className="text-[13px] text-[rgba(0,0,0,0.4)] mb-6 text-center max-w-sm leading-relaxed">
      {body}
    </p>
    {action}
  </div>
);

export const WarNoAccountState: React.FC = () => (
  <EmptyShell
    icon="mdi:cloud-question-outline"
    title="Select an account"
    body="Pick a cloud account from the selector to review what in its infrastructure could
      let an attacker or crypto-miner in."
  />
);

export const WarErrorState: React.FC<{ message: string; onRetry: () => void }> = ({
  message,
  onRetry,
}) => (
  <EmptyShell
    icon="mdi:alert-circle-outline"
    iconClass="text-red-400"
    frameClass="bg-red-50 border-red-100"
    title="Could not complete the review"
    body={message}
    action={
      <button onClick={onRetry} className={BTN_PRIMARY}>
        <Icon icon="mdi:refresh" width={14} />
        Try again
      </button>
    }
  />
);

/**
 * Shown when the selected account is not AWS.
 *
 * The GCP and Azure baselines are not transcribed yet, and silently showing an empty
 * report would read as "no problems found" — the opposite of the truth.
 */
export const WarProviderNotReadyState: React.FC<{ provider: string }> = ({ provider }) => (
  <EmptyShell
    icon="mdi:progress-wrench"
    title={`WAR for ${provider} is not available yet`}
    body={
      <>
        The AWS baseline covers 86 controls today. The {provider} equivalent is being built
        from the same advisory format. Select an AWS account to run a review now.
      </>
    }
  />
);
