/**
 * DetailDrawer (FEAT-001; docs/cloudk8s/ui-revamp-design.md §8.1): a right slide-in panel that
 * replaces K8sDataTable's inline row-expand on the flagship tabs. It is a real modal dialog —
 * `role="dialog" aria-modal="true"`, a focus trap, Esc and scrim both close, and focus returns to
 * the element that opened it. The panel is min(480px, 100%), rounded on its left edge only, on the
 * `--chrome-card` surface with one `--chrome-line` hairline and NO shadow; the scrim is an
 * equal-channel rgba(0,0,0,0.32). The 150 ms fade + translateX is skipped under
 * `prefers-reduced-motion`. Interactive targets are 44px.
 *
 * Honesty rules (CLAUDE.md): a `DrawerDatum` whose value is null renders the muted em-dash (DASH)
 * with its reason as a tooltip, never a 0 or a made-up value — and a whole `DrawerSection` is DROPPED
 * before render when every one of its data is null (so a section never shows as a wall of dashes).
 */
import { useCallback, useEffect, useId, useRef, type ReactNode } from 'react';
import { Icon } from '@iconify/react';
import { DASH } from '../../../utils/k8sQuantities';
import { SECTION, TITLE, LABEL, BODY } from './typeScale';

export interface DrawerDatum {
  label: string;
  /** The value; `null` renders `<Dash>` with `reason` as its tooltip. */
  value: ReactNode | null;
  /** Why the value is missing, shown as the dash's tooltip. */
  reason?: string;
}

export interface DrawerSection {
  title: string;
  data: readonly DrawerDatum[];
}

export interface DetailDrawerProps {
  open: boolean;
  onClose: () => void;
  /** The drawer's heading. */
  title: string;
  /** Optional sub-heading under the title. */
  subtitle?: ReactNode;
  sections?: readonly DrawerSection[];
  /** Freeform content rendered after the sections. */
  children?: ReactNode;
  loading?: boolean;
}

/** `true` when a datum has no value (so an all-empty section can be dropped). */
const isEmptyDatum = (d: DrawerDatum) => d.value === null || d.value === undefined || d.value === DASH;

function Dash({ reason }: { reason?: string }) {
  return <span className="text-[color:var(--chrome-muted)]" title={reason}>{DASH}</span>;
}

function Section({ section }: { section: DrawerSection }) {
  return (
    <section className="flex flex-col gap-2 border-b border-[color:var(--chrome-line)] px-5 py-4 last:border-b-0">
      <h3 className={TITLE}>{section.title}</h3>
      <dl className="grid grid-cols-[minmax(0,1fr)_minmax(0,1.4fr)] gap-x-4 gap-y-1.5">
        {section.data.map((d) => (
          <div key={d.label} className="contents">
            <dt className={LABEL}>{d.label}</dt>
            <dd className={`${BODY} min-w-0 break-words`}>
              {isEmptyDatum(d) ? <Dash reason={d.reason} /> : d.value}
            </dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

function SkeletonBody() {
  return (
    <div className="flex flex-col gap-4 px-5 py-4" aria-hidden="true">
      {[0, 1, 2].map((i) => (
        <div key={i} className="flex flex-col gap-2">
          <span className="h-3 w-24 rounded bg-[rgba(0,0,0,0.06)] dark:bg-[rgba(255,255,255,0.08)]" />
          <span className="h-4 w-full rounded bg-[rgba(0,0,0,0.06)] dark:bg-[rgba(255,255,255,0.08)]" />
          <span className="h-4 w-2/3 rounded bg-[rgba(0,0,0,0.06)] dark:bg-[rgba(255,255,255,0.08)]" />
        </div>
      ))}
    </div>
  );
}

const FOCUSABLE = 'a[href],button:not([disabled]),textarea,input,select,[tabindex]:not([tabindex="-1"])';

export function DetailDrawer({
  open, onClose, title, subtitle, sections = [], children, loading = false,
}: DetailDrawerProps) {
  const titleId = useId();
  const panelRef = useRef<HTMLDivElement | null>(null);
  const openerRef = useRef<Element | null>(null);

  // Drop any section whose every datum is empty, so a section never renders as a wall of dashes.
  const visibleSections = sections.filter((s) => s.data.some((d) => !isEmptyDatum(d)));

  const handleKeyDown = useCallback((event: React.KeyboardEvent) => {
    if (event.key === 'Escape') {
      event.stopPropagation();
      onClose();
      return;
    }
    if (event.key !== 'Tab') return;
    const panel = panelRef.current;
    if (!panel) return;
    const focusable = Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE)).filter((el) => el.offsetParent !== null || el === document.activeElement);
    if (focusable.length === 0) {
      event.preventDefault();
      panel.focus();
      return;
    }
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    const active = document.activeElement;
    if (event.shiftKey && (active === first || active === panel)) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && active === last) {
      event.preventDefault();
      first.focus();
    }
  }, [onClose]);

  // On open: remember the opener, move focus into the panel. On close: return focus.
  useEffect(() => {
    if (!open) return;
    openerRef.current = document.activeElement;
    const panel = panelRef.current;
    const focusTarget = panel?.querySelector<HTMLElement>(FOCUSABLE) ?? panel;
    focusTarget?.focus();
    return () => {
      if (openerRef.current instanceof HTMLElement) openerRef.current.focus();
    };
  }, [open]);

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex justify-end" data-detail-drawer="">
      <div
        className="absolute inset-0 bg-[rgba(0,0,0,0.32)] motion-safe:transition-opacity motion-safe:duration-150"
        aria-hidden="true"
        onClick={onClose}
        data-drawer-scrim=""
      />
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        onKeyDown={handleKeyDown}
        className="relative flex h-full w-[min(480px,100%)] flex-col overflow-hidden rounded-l-[8px] border-l border-[color:var(--chrome-line)] bg-[color:var(--chrome-card)] outline-none motion-safe:transition-transform motion-safe:duration-150"
      >
        <header className="flex items-start justify-between gap-3 border-b border-[color:var(--chrome-line)] px-5 py-4">
          <div className="min-w-0">
            <h2 id={titleId} className={SECTION}>{title}</h2>
            {subtitle !== undefined && subtitle !== null && (
              <p className="mt-0.5 text-[12px] text-[color:var(--chrome-muted)]">{subtitle}</p>
            )}
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="-mr-2 -mt-1 flex h-11 w-11 shrink-0 items-center justify-center rounded-md text-[color:var(--chrome-muted)] hover:bg-[rgba(0,0,0,0.04)] hover:text-[color:var(--chrome-ink)] dark:hover:bg-[rgba(255,255,255,0.06)] outline-none focus-visible:ring-2 focus-visible:ring-[color:var(--chrome-ink)]"
          >
            <Icon icon="mdi:close" width={20} aria-hidden="true" />
          </button>
        </header>
        <div className="min-h-0 flex-1 overflow-y-auto">
          {loading ? <SkeletonBody /> : (
            <>
              {visibleSections.map((section) => <Section key={section.title} section={section} />)}
              {children}
            </>
          )}
        </div>
      </div>
    </div>
  );
}

export default DetailDrawer;
