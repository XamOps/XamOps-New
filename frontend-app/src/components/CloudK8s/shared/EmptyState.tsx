/**
 * EmptyState (FEAT-001): the one honest empty-panel pattern — a single mdi icon, one sentence, and
 * at most one action button. No sample data, no decorative fluff.
 *
 * The optional blurred backdrop blurs a SKELETON (grey placeholder bars), never sample or fabricated
 * data — that keeps the "never fabricate" rule intact (CLAUDE.md): the blur hints at the shape of
 * what will arrive, not at numbers that don't exist.
 */
import { Icon } from '@iconify/react';
import { BODY } from './typeScale';

export interface EmptyStateProps {
  /** A bundled mdi:* icon id. */
  icon: string;
  /** One sentence describing the empty state. */
  message: string;
  /** At most one action. */
  action?: { label: string; onClick: () => void };
  /** Blur a skeleton behind the message (never sample data). */
  blurredBackdrop?: boolean;
  className?: string;
}

const buttonClass =
  'inline-flex items-center gap-1.5 rounded-md border border-[color:var(--chrome-line)] bg-[color:var(--chrome-card)] px-3 py-1.5 text-[13px] font-semibold text-[color:var(--chrome-ink)] hover:bg-[rgba(0,0,0,0.04)] dark:hover:bg-[rgba(255,255,255,0.06)] outline-none focus-visible:ring-2 focus-visible:ring-[color:var(--chrome-ink)] focus-visible:ring-offset-2';

function SkeletonBackdrop() {
  // A blurred grey skeleton — placeholder bars only, no numbers, no sample data.
  return (
    <div
      className="pointer-events-none absolute inset-0 flex flex-col gap-3 p-6 opacity-40 blur-sm"
      aria-hidden="true"
      data-empty-skeleton=""
    >
      {[0, 1, 2, 3].map((i) => (
        <div key={i} className="h-5 w-full rounded bg-[rgba(0,0,0,0.08)] dark:bg-[rgba(255,255,255,0.08)]" />
      ))}
    </div>
  );
}

export function EmptyState({ icon, message, action, blurredBackdrop = false, className = '' }: EmptyStateProps) {
  return (
    <div
      className={`relative flex min-h-[160px] flex-col items-center justify-center gap-3 px-6 py-10 text-center ${className}`.trim()}
      data-empty-state=""
    >
      {blurredBackdrop && <SkeletonBackdrop />}
      <div className="relative flex flex-col items-center gap-3">
        <Icon icon={icon} width={28} aria-hidden="true" className="text-[color:var(--chrome-muted)]" />
        <p className={`max-w-[42ch] ${BODY}`}>{message}</p>
        {action && (
          <button type="button" onClick={action.onClick} className={buttonClass}>
            {action.label}
          </button>
        )}
      </div>
    </div>
  );
}

export default EmptyState;
