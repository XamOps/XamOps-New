import React from 'react';
import { Icon } from '@iconify/react';
import type { WarReport } from '../../types/war';

interface Props {
  report: WarReport;
  onRetry: () => void;
}

/**
 * Recognised scan-blocking causes, mapped to what the operator should actually do.
 *
 * Matching on the AWS error text is admittedly brittle, but the alternative is showing
 * a raw SDK exception to someone who just wants to know whether their account is safe.
 * Unrecognised causes fall through to the generic branch with the message intact.
 */
const CAUSES: Array<{ match: RegExp; title: string; action: string }> = [
  {
    match: /security token included in the request is invalid|ExpiredToken|InvalidClientTokenId/i,
    title: 'XamOps could not authenticate to this AWS account',
    action:
      'The cross-account role could not be assumed — the credentials XamOps uses to call '
      + 'AssumeRole are expired or invalid. This blocks every AWS-backed page, not just '
      + 'WAR. Refresh the platform credentials, then re-scan.',
  },
  {
    match: /invalid_grant|could not create GoogleCredentials|credentials.*missing|PERMISSION_DENIED|caller does not have permission/i,
    title: 'XamOps could not authenticate to this Google Cloud project',
    action:
      'The service account key stored for this project is invalid, revoked, or missing '
      + 'the roles these checks need. Re-upload the key or grant the missing roles, then '
      + 're-scan.',
  },
  {
    match: /has not been used in project|API has not been enabled|SERVICE_DISABLED/i,
    title: 'Required Google Cloud APIs are not enabled',
    action:
      'The checks call APIs that are disabled on this project. Enable the services named '
      + 'below in the Cloud Console, then re-scan.',
  },
  {
    match: /is not authorized to perform|AccessDenied|not authorized/i,
    title: 'The XamOps role is missing read permissions',
    action:
      'The role was assumed successfully but denied the read-only calls these checks need. '
      + 'Grant the actions listed below, then re-scan.',
  },
  {
    match: /Unable to execute HTTP request|connect timed out|UnknownHost/i,
    title: 'AWS endpoints were unreachable from the XamOps host',
    action:
      'The scan could not open connections to the AWS APIs. This usually means missing NAT '
      + 'or egress from the host running XamOps.',
  },
];

/**
 * One banner explaining why a scan produced no verdicts.
 *
 * This exists because the alternative was a "Critical risk, score 0" headline over a
 * collapsed list of a hundred identical warnings. That reads as "your account is
 * dangerously insecure" when the truth is "we could not look at your account" — a
 * materially different message that leads to a materially different action.
 */
const WarBlockedBanner: React.FC<Props> = ({ report, onRetry }) => {
  if (report.assessable || !report.blockedReason) {
    return null;
  }

  const cause = CAUSES.find((c) => c.match.test(report.blockedReason ?? ''));
  const unknown = report.statusCounts.UNKNOWN ?? 0;

  return (
    <div
      className="bg-white border border-[#e5e7eb] border-l-[3px] border-l-red-500 rounded-xl p-5"
      role="alert"
    >
      <div className="flex items-start gap-3">
        <div className="w-10 h-10 rounded-xl bg-red-50 border border-red-100 flex items-center justify-center shrink-0">
          <Icon icon="mdi:cloud-off-outline" width={19} className="text-red-500" />
        </div>

        <div className="min-w-0 flex-1">
          <h2 className="text-[13.5px] font-semibold text-[rgba(0,0,0,0.88)]">
            {cause?.title
              ?? `This scan could not read your ${
                report.provider === 'GCP' ? 'Google Cloud project' : 'AWS account'
              }`}
          </h2>

          <p className="mt-1 text-[12.5px] text-[rgba(0,0,0,0.6)] leading-relaxed max-w-3xl">
            {cause?.action
              ?? 'Every control check failed for the same reason, so no verdict could be '
                + 'reached on any of them.'}
          </p>

          <div className="mt-3 flex items-start gap-2 flex-wrap">
            <span className="text-[10px] font-semibold uppercase tracking-widest text-[rgba(0,0,0,0.35)] shrink-0 mt-1">
              {report.provider === 'GCP' ? 'Google Cloud said' : 'AWS said'}
            </span>
            <code className="text-[11px] font-mono text-[rgba(0,0,0,0.7)] bg-[#fafafa] border border-[#e5e7eb] rounded px-2 py-1 leading-relaxed break-words">
              {report.blockedReason}
            </code>
          </div>

          <div className="mt-3 flex items-start gap-3 px-4 py-3 rounded-xl border bg-red-50 text-red-700 border-red-200">
            <span className="w-2 h-2 rounded-full bg-red-500 animate-pulse shrink-0 mt-1.5" />
            <p className="text-[11.5px] leading-relaxed">
              <span className="font-semibold">The score above is not a security verdict.</span>{' '}
              {unknown} of {report.totalControls} controls are unverified because the scan was
              blocked, not because they failed.
              {report.verifiedControls > 0 && (
                <>
                  {' '}
                  The {report.verifiedControls} control
                  {report.verifiedControls === 1 ? '' : 's'} that did complete are shown below
                  and are real findings — but they are too small a sample to rate the account.
                </>
              )}
            </p>
          </div>

          <button
            onClick={onRetry}
            className="mt-3 flex items-center gap-1.5 px-3 py-1.5 bg-[rgba(0,0,0,0.88)] text-white rounded-md text-[12px] font-medium hover:bg-black transition-colors"
          >
            <Icon icon="mdi:refresh" width={14} />
            Re-scan
          </button>
        </div>
      </div>
    </div>
  );
};

export default WarBlockedBanner;
