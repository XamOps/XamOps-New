import React, { useCallback, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Icon } from '@iconify/react';
import {
  executeStackUpdate,
  fetchStackProgress,
  previewStackUpdate,
  StackUpdateError,
  type CurApplyResult,
  type StackUpdatePreview,
  type StackUpdateProgress,
} from '../../api/stackApi';

/**
 * Updates a connected account's CloudFormation stack from here, instead of in the AWS console.
 *
 * <p>Three states, in order: what would change, then running, then done. The preview is not a
 * nicety — this stack owns the IAM role the whole connection depends on, so seeing the resource
 * list before anything runs is the only protection against an update that replaces it.
 *
 * <p>Events stream while it runs because a stack update takes minutes and can roll back. A
 * spinner with no detail turns a recoverable rollback into a support ticket.
 */

interface StackUpdateModalProps {
  accountId: string;
  accountName: string;
  onClose: () => void;
}

type Phase = 'previewing' | 'preview' | 'running' | 'settled' | 'refused';

/** Colour by what the action does to the resource, not by severity in the abstract. */
const ACTION_TONE: Record<string, string> = {
  Add: 'bg-emerald-50 text-emerald-700 border-emerald-200',
  Modify: 'bg-blue-50 text-blue-700 border-blue-200',
  Remove: 'bg-rose-50 text-rose-600 border-rose-200',
  Dynamic: 'bg-amber-50 text-amber-700 border-amber-200',
};

/**
 * CUR statuses the customer can act on but no role can grant itself — shown as amber notes,
 * never as the grantUrl link reserved for the connection side.
 */
const CUR_PERMISSION_STATUSES = new Set(['PENDING_PERMISSION', 'NO_PERMISSION_TERMINAL']);

const StackUpdateModal: React.FC<StackUpdateModalProps> = ({
  accountId,
  accountName,
  onClose,
}) => {
  const [phase, setPhase] = useState<Phase>('previewing');
  const [preview, setPreview] = useState<StackUpdatePreview | null>(null);
  const [progress, setProgress] = useState<StackUpdateProgress | null>(null);
  // The CUR submit outcome, seeded from executeStackUpdate so the CUR line shows a status
  // before the first progress poll returns its own `cur` sub-object.
  const [curSeed, setCurSeed] = useState<CurApplyResult | null>(null);
  const [error, setError] = useState<{
    message: string;
    remedy?: string;
    grantUrl?: string;
    grantRoleName?: string;
  } | null>(null);
  const pollRef = useRef<number | null>(null);

  useEffect(() => {
    let cancelled = false;
    previewStackUpdate(accountId)
      .then((p) => {
        if (cancelled) return;
        setPreview(p);
        setPhase('preview');
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        const err = e as StackUpdateError;
        setError({ message: err.message, remedy: err.remedy, grantUrl: err.grantUrl, grantRoleName: err.grantRoleName });
        setPhase('refused');
      });
    return () => {
      cancelled = true;
    };
  }, [accountId]);

  // Stops itself once the stack settles, so a modal left open does not poll forever.
  const startPolling = useCallback(() => {
    const tick = async () => {
      try {
        const p = await fetchStackProgress(accountId);
        setProgress(p);
        // Settle only when BOTH the connection stack and the CUR stack have stopped moving.
        // A missing `cur` (null) means there is no CUR side to wait on.
        const curInProgress = p.cur?.inProgress ?? false;
        if (!p.inProgress && !curInProgress) {
          setPhase('settled');
          if (pollRef.current) window.clearInterval(pollRef.current);
          pollRef.current = null;
        }
      } catch {
        // A transient read failure mid-update is not worth tearing the view down; the next
        // tick usually succeeds, and the stack carries on regardless of what we can see.
      }
    };
    void tick();
    pollRef.current = window.setInterval(tick, 5000);
  }, [accountId]);

  useEffect(
    () => () => {
      if (pollRef.current) window.clearInterval(pollRef.current);
    },
    [],
  );

  // Lock the page behind the modal. The overlay is portalled to <body>, so the
  // scroll container it used to live in (#main-content) would otherwise keep
  // scrolling behind it. Restore whatever overflow the document had on close.
  useEffect(() => {
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = previous;
    };
  }, []);

  // Close on Escape, matching the click-outside affordance (but never mid-run).
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && phase !== 'running') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [phase, onClose]);

  const run = async () => {
    // A CUR-only apply has no connection change set — allow it as long as there is some
    // change to make (either a connection change set or a CUR change row).
    if (!preview?.changeSetId && !preview?.curChange) return;
    setPhase('running');
    try {
      const result = await executeStackUpdate(accountId, preview.changeSetId);
      setCurSeed(result.cur);
      startPolling();
    } catch (e: unknown) {
      const err = e as StackUpdateError;
      setError({ message: err.message, remedy: err.remedy, grantUrl: err.grantUrl, grantRoleName: err.grantRoleName });
      setPhase('refused');
    }
  };

  const replacesRole = preview?.resourceChanges.some(
    (c) => c.replacement === 'True' && c.resourceType === 'AWS::IAM::Role',
  );

  // Portalled to <body> so the overlay anchors to the viewport, not to the
  // transformed Framer Motion page wrapper / scrollable #main-content it renders
  // inside. A `position: fixed` element is contained by any ancestor with a
  // transform, so without this the overlay left a gap under the topbar and let
  // the page scroll behind it.
  return createPortal(
    <div
      className="fixed inset-0 flex items-center justify-center z-50 p-4"
      onClick={phase === 'running' ? undefined : onClose}
    >
      {/* Scrim only — kept off the sidebar (fixed 220px, lg+) so the nav stays
          sharp while the content area behind the dialog dims and blurs. Below lg
          the sidebar is off-canvas, so the scrim covers the whole viewport. */}
      <div
        aria-hidden="true"
        className="fixed inset-0 lg:left-[220px] bg-black/40 backdrop-blur-sm pointer-events-none"
      />
      <div
        className="relative bg-white rounded-2xl shadow-2xl w-full max-w-2xl max-h-[85vh] flex flex-col"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start gap-3 px-6 pt-6 pb-4 border-b border-[rgba(0,0,0,0.07)]">
          <div className="w-9 h-9 rounded-xl bg-blue-50 text-blue-600 flex items-center justify-center flex-shrink-0">
            <Icon icon="mdi:cloud-sync-outline" width={19} />
          </div>
          <div className="min-w-0">
            <h3 className="text-[15px] font-bold text-black">Update stack</h3>
            <p className="text-[12px] text-[rgba(0,0,0,0.45)] mt-0.5">
              {accountName}
              {preview?.stackName && (
                <>
                  {' · '}
                  <span className="font-mono">{preview.stackName}</span>
                  {' · '}
                  {preview.region}
                </>
              )}
            </p>
          </div>
          <button
            onClick={onClose}
            disabled={phase === 'running'}
            className="ml-auto text-[rgba(0,0,0,0.4)] hover:text-black disabled:opacity-30"
            aria-label="Close"
          >
            <Icon icon="mdi:close" width={18} />
          </button>
        </div>

        <div className="px-6 py-5 overflow-y-auto flex-1">
          {phase === 'previewing' && (
            <div className="flex items-center gap-2.5 text-[13px] text-[rgba(0,0,0,0.55)] py-6">
              <Icon icon="mdi:loading" width={16} className="animate-spin" />
              Building a change set to see what would change…
            </div>
          )}

          {phase === 'refused' && error && (
            <div className="space-y-3">
              <div className="flex gap-2.5 rounded-lg border border-amber-200 bg-amber-50 px-3.5 py-3">
                <Icon
                  icon="mdi:alert-outline"
                  width={17}
                  className="text-amber-600 flex-shrink-0 mt-0.5"
                />
                <p className="text-[12.5px] text-amber-900 leading-relaxed">{error.message}</p>
              </div>
              {error.remedy && (
                <div className="rounded-lg bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.07)] px-3.5 py-3">
                  <p className="text-[10.5px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.4)]">
                    What to do
                  </p>
                  <p className="text-[13px] text-black mt-1">{error.remedy}</p>
                  {/* The one-click fix for an account connected before onboarding carried the
                      updater. Opens AWS rather than acting, because no role can grant itself
                      permissions — someone with access to the account has to approve it. */}
                  {error.grantUrl && (
                    <div className="mt-3 flex flex-wrap items-center gap-x-3 gap-y-2">
                      <a
                        href={error.grantUrl}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="inline-flex items-center gap-1.5 px-3.5 py-2 rounded-lg text-[12.5px] font-medium
                                   bg-[rgba(0,0,0,0.88)] text-white hover:bg-[rgba(0,0,0,0.75)] transition-colors"
                      >
                        <Icon icon="mdi:open-in-new" width={14} />
                        Approve in AWS console
                      </a>
                      {error.grantRoleName && (
                        <span className="text-[11.5px] text-[rgba(0,0,0,0.45)]">
                          Grants to role{' '}
                          <code className="font-mono text-[rgba(0,0,0,0.7)]">{error.grantRoleName}</code>
                        </span>
                      )}
                    </div>
                  )}
                </div>
              )}
            </div>
          )}

          {phase === 'preview' && preview?.noChanges && !preview.curChange && (
            <div className="text-center py-8">
              <Icon
                icon="mdi:check-circle-outline"
                width={30}
                className="text-emerald-500 mx-auto mb-2"
              />
              <p className="text-[13.5px] text-black font-medium">Already up to date</p>
              <p className="text-[12px] text-[rgba(0,0,0,0.5)] mt-1">{preview.detail}</p>
            </div>
          )}

          {phase === 'preview' && preview && (!preview.noChanges || preview.curChange) && (
            <div className="space-y-5">
              {preview.parameterChanges.length > 0 && (
                <div>
                  <h4 className="text-[10.5px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.4)] mb-2">
                    Parameters
                  </h4>
                  <div className="space-y-1.5">
                    {preview.parameterChanges.map((p) => (
                      <div
                        key={p.name}
                        className="rounded-lg border border-[rgba(0,0,0,0.08)] px-3 py-2"
                      >
                        <p className="text-[12.5px] font-semibold text-black">{p.name}</p>
                        <p className="text-[11px] font-mono text-[rgba(0,0,0,0.4)] mt-1 break-all line-through">
                          {p.from || '(empty)'}
                        </p>
                        <p className="text-[11px] font-mono text-emerald-700 break-all">{p.to}</p>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              <div>
                <h4 className="text-[10.5px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.4)] mb-2">
                  Resources ({preview.resourceChanges.length + (preview.curChange ? 1 : 0)})
                </h4>
                <div className="space-y-1">
                  {preview.resourceChanges.map((c) => (
                    <div
                      key={`${c.logicalId}-${c.action}`}
                      className="flex items-center gap-2.5 px-3 py-2 rounded-lg border border-[rgba(0,0,0,0.07)]"
                    >
                      <span
                        className={`text-[10.5px] font-semibold px-1.5 py-0.5 rounded border ${
                          ACTION_TONE[c.action] ?? 'bg-[rgba(0,0,0,0.05)] text-[rgba(0,0,0,0.6)] border-transparent'
                        }`}
                      >
                        {c.action}
                      </span>
                      <span className="text-[12.5px] font-medium text-black truncate">
                        {c.logicalId}
                      </span>
                      <span className="text-[11px] text-[rgba(0,0,0,0.4)] truncate ml-auto">
                        {c.resourceType}
                      </span>
                      {c.replacement === 'True' && (
                        <span className="text-[10.5px] font-semibold text-rose-600 flex-shrink-0">
                          replaced
                        </span>
                      )}
                    </div>
                  ))}

                  {/* The CUR report stack rides along on the same Update button. Create tones
                      like an Add, Update like a Modify, matching the connection rows above. */}
                  {preview.curChange && (
                    <div className="flex items-center gap-2.5 px-3 py-2 rounded-lg border border-[rgba(0,0,0,0.07)]">
                      <span
                        className={`text-[10.5px] font-semibold px-1.5 py-0.5 rounded border ${
                          ACTION_TONE[preview.curChange.action === 'Create' ? 'Add' : 'Modify']
                        }`}
                      >
                        {preview.curChange.action === 'Create' ? 'Add' : 'Modify'}
                      </span>
                      <span className="text-[12.5px] font-medium text-black truncate">
                        XamOpsCurReport
                      </span>
                      <span className="text-[11px] text-[rgba(0,0,0,0.4)] truncate ml-auto">
                        AWS::CloudFormation::Stack · us-east-1
                      </span>
                      {preview.curChange.status.includes('_IN_PROGRESS') && (
                        <span className="text-[10.5px] font-semibold text-amber-600 flex-shrink-0">
                          in progress — will retry
                        </span>
                      )}
                    </div>
                  )}
                </div>
              </div>

              {/* Replacing the connection role reissues it, which breaks the trust
                  relationship XamOps assumes through. Worth stopping for. */}
              {replacesRole && (
                <div className="flex gap-2.5 rounded-lg border border-rose-200 bg-rose-50 px-3.5 py-3">
                  <Icon
                    icon="mdi:alert-octagon-outline"
                    width={17}
                    className="text-rose-600 flex-shrink-0 mt-0.5"
                  />
                  <p className="text-[12.5px] text-rose-900 leading-relaxed">
                    This update <strong>replaces an IAM role</strong>. If that is the connection
                    role, the account will be disconnected. Check the change list before
                    continuing.
                  </p>
                </div>
              )}
            </div>
          )}

          {(phase === 'running' || phase === 'settled') && (
            <div className="space-y-3">
              <div className="flex items-center gap-2.5">
                {phase === 'running' ? (
                  <Icon icon="mdi:loading" width={16} className="animate-spin text-blue-600" />
                ) : (
                  <Icon
                    icon={progress?.failed ? 'mdi:alert-circle-outline' : 'mdi:check-circle-outline'}
                    width={17}
                    className={progress?.failed ? 'text-rose-500' : 'text-emerald-500'}
                  />
                )}
                <span className="text-[13px] font-mono text-black">
                  {progress?.status ?? 'starting…'}
                </span>
              </div>

              {/* The CUR stack runs in its own region alongside the connection stack. Show its
                  own line, seeded from the submit outcome until the first progress poll
                  returns a `cur` sub-object. */}
              {(() => {
                const curStatus = progress?.cur?.status ?? curSeed?.curStatus;
                const curFailed = progress?.cur?.failed ?? false;
                const curSettling = phase === 'settled' || progress?.cur?.inProgress === false;
                if (!curStatus) return null;
                return (
                  <div className="flex items-center gap-2.5">
                    {phase === 'running' && !curSettling ? (
                      <Icon icon="mdi:loading" width={16} className="animate-spin text-blue-600" />
                    ) : (
                      <Icon
                        icon={curFailed ? 'mdi:alert-circle-outline' : 'mdi:check-circle-outline'}
                        width={17}
                        className={curFailed ? 'text-rose-500' : 'text-emerald-500'}
                      />
                    )}
                    <span className="text-[13px] font-mono text-black">
                      CUR (us-east-1): {curStatus}
                    </span>
                  </div>
                );
              })()}

              {/* A CUR permission refusal is the customer's to fix in AWS, but no role can
                  grant itself permission — so it shows as an amber note, never a grant link. */}
              {(() => {
                const curStatus = progress?.cur?.status ?? curSeed?.curStatus;
                if (!curStatus || !CUR_PERMISSION_STATUSES.has(curStatus)) return null;
                return (
                  <div className="flex gap-2.5 rounded-lg border border-amber-200 bg-amber-50 px-3.5 py-3">
                    <Icon
                      icon="mdi:alert-outline"
                      width={17}
                      className="text-amber-600 flex-shrink-0 mt-0.5"
                    />
                    <p className="text-[12.5px] text-amber-900 leading-relaxed">
                      {curSeed?.message ?? 'The CUR stack needs a permission that must be approved in AWS.'}
                    </p>
                  </div>
                );
              })()}

              <div className="rounded-lg border border-[rgba(0,0,0,0.08)] divide-y divide-[rgba(0,0,0,0.05)] max-h-[300px] overflow-y-auto">
                {(progress?.events ?? []).map((e, i) => (
                  <div key={`${e.timestamp}-${e.logicalId}-${i}`} className="px-3 py-1.5">
                    <div className="flex items-baseline gap-2">
                      <span className="text-[11.5px] font-medium text-black truncate">
                        {e.logicalId}
                      </span>
                      <span
                        className={`text-[10.5px] font-mono ml-auto flex-shrink-0 ${
                          e.status.includes('FAILED')
                            ? 'text-rose-600'
                            : e.status.includes('COMPLETE')
                              ? 'text-emerald-600'
                              : 'text-[rgba(0,0,0,0.45)]'
                        }`}
                      >
                        {e.status}
                      </span>
                    </div>
                    {e.reason && (
                      <p className="text-[10.5px] text-[rgba(0,0,0,0.5)] mt-0.5 leading-snug">
                        {e.reason}
                      </p>
                    )}
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>

        <div className="flex items-center justify-end gap-2 px-6 py-4 bg-[rgba(0,0,0,0.02)] border-t border-[rgba(0,0,0,0.07)]">
          <button
            onClick={onClose}
            disabled={phase === 'running'}
            className="h-9 px-4 rounded-lg text-[12.5px] font-semibold text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.05)] disabled:opacity-30"
          >
            {phase === 'settled' ? 'Done' : 'Cancel'}
          </button>
          {phase === 'preview' && preview && (!preview.noChanges || preview.curChange) && (
            <button
              onClick={run}
              className="h-9 px-4 rounded-lg bg-blue-600 text-white text-[12.5px] font-semibold hover:bg-blue-700"
            >
              {(() => {
                const count = preview.resourceChanges.length + (preview.curChange ? 1 : 0);
                return `Apply ${count} change${count === 1 ? '' : 's'}`;
              })()}
            </button>
          )}
        </div>
      </div>
    </div>,
    document.body,
  );
};

export default StackUpdateModal;
