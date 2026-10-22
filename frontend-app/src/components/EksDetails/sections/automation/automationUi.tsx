/**
 * Chrome shared by the Automation pages (Overview, Workloads, workload detail, Policies): the grey
 * card, ink and rule classes with their dark variants, in-page links, the one-time notice and the
 * copy for an endpoint-1 read that didn't answer with a view. Greyscale only: colour reaches these
 * pages through shared/status and shared/charts.
 *
 * The card, the notice and the read-failure card mark their root `data-k8s-kit`, like the kit's
 * own components: every colour in them has its dark variant, so style.css's legacy dark rules
 * (which repaint buttons, white surfaces and text with `!important`) must not reach them.
 */
import type { ReactNode } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { Icon } from '@iconify/react';
import { inPageState } from '../../../../utils/inPageNavigation';
import { codeText } from '../../../../api/verticalRightsizerAdapter';
import type { CodeView } from '../../../../types/verticalRightsizer';
import { Surface } from '../../../CloudK8s/shared/Surface';

/**
 * The automation pages' card chrome is the one Surface `grouped` tier (DESIGN §3): CARD carries the
 * tier's radius + border + bg so a card that can't use the component (ReadFailureCard) still matches
 * it, and Card itself delegates to <Surface tier="grouped">. Border aligned to the Surface token
 * rgba(0,0,0,0.07)/dark 0.09 and the one radius rounded-[8px] (matches Surface's grouped tier).
 */
export const CARD = 'rounded-[8px] border border-[rgba(0,0,0,0.07)] bg-white dark:border-[rgba(255,255,255,0.09)] dark:bg-[#141414]';
export const INK = 'text-[rgba(0,0,0,0.88)] dark:text-[rgba(255,255,255,0.92)]';
export const MUTED = 'text-[rgba(0,0,0,0.6)] dark:text-[rgba(255,255,255,0.6)]';
/** Tertiary text (source lines, gate reasons, dash explanations): 0.56 is 4.9:1 on white, AA at 11–12 px. */
export const FAINT = 'text-[rgba(0,0,0,0.56)] dark:text-[rgba(255,255,255,0.5)]';
export const RULE = 'border-[rgba(0,0,0,0.08)] dark:border-[rgba(255,255,255,0.1)]';
/** RULE's colours for the lines `divide-y` draws between children (a parent's border colour doesn't reach them). */
export const DIVIDE = 'divide-[rgba(0,0,0,0.08)] dark:divide-[rgba(255,255,255,0.1)]';
export const FOCUS = 'outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.88)] dark:focus-visible:ring-[rgba(255,255,255,0.92)]';
export const LINK = `rounded-[6px] font-semibold underline underline-offset-2 ${INK} ${FOCUS}`;
export const BUTTON = `rounded-[6px] border border-[rgba(0,0,0,0.12)] bg-white px-3 py-1.5 text-[12px] font-semibold text-[rgba(0,0,0,0.72)] hover:bg-[rgba(0,0,0,0.03)] dark:border-[rgba(255,255,255,0.16)] dark:bg-[#141414] dark:text-[rgba(255,255,255,0.8)] dark:hover:bg-[rgba(255,255,255,0.06)] ${FOCUS}`;
export const CONTROL = `rounded-[6px] border border-[rgba(0,0,0,0.12)] bg-white px-2 py-1.5 text-[12px] text-[rgba(0,0,0,0.88)] dark:border-[rgba(255,255,255,0.16)] dark:bg-[#141414] dark:text-[rgba(255,255,255,0.92)] ${FOCUS}`;

/** A link to another view of this page: the page stays mounted, so nothing is read again. */
export function InPageLink({
  to,
  children,
  className = LINK,
  ...rest
}: { to: string; children: ReactNode; className?: string; 'aria-label'?: string; title?: string }) {
  const location = useLocation();
  // data-k8s-kit: the link carries its own dark colours, so style.css's legacy dark link rule
  // (which turns every link blue) leaves it alone.
  return (
    <Link to={to} state={inPageState(location)} className={className} data-k8s-kit="" {...rest}>
      {children}
    </Link>
  );
}

/** A one-time notice about this page's link, with Dismiss. */
export function Notice({ children, onDismiss }: { children: ReactNode; onDismiss?: () => void }) {
  return (
    <div
      role="status"
      className={`flex items-center gap-3 rounded-[8px] border border-[rgba(0,0,0,0.12)] bg-[rgba(0,0,0,0.03)] px-3.5 py-2.5 text-[13px] dark:border-[rgba(255,255,255,0.14)] dark:bg-[rgba(255,255,255,0.04)] ${INK}`}
      data-k8s-kit=""
    >
      <Icon icon="mdi:information-outline" width={16} aria-hidden="true" className={`shrink-0 ${MUTED}`} />
      <span className="min-w-0 flex-1">{children}</span>
      {onDismiss && (
        <button type="button" onClick={onDismiss} aria-label="Dismiss" className={`rounded-[6px] p-0.5 ${MUTED} ${FOCUS}`}>
          <Icon icon="mdi:close" width={16} aria-hidden="true" />
        </button>
      )}
    </div>
  );
}

/** A neutral grey tag: where a value comes from, never a status. */
export function Tag({ icon, children }: { icon: string; children: ReactNode }) {
  return (
    <span className="inline-flex items-center gap-1 rounded-[6px] border border-[rgba(0,0,0,0.12)] px-2 py-0.5 text-[11px] font-semibold text-[rgba(0,0,0,0.6)] dark:border-[rgba(255,255,255,0.16)] dark:text-[rgba(255,255,255,0.6)]">
      <Icon icon={icon} width={12} aria-hidden="true" className="shrink-0" />
      <span>{children}</span>
    </span>
  );
}

/** A card section with an h3 title (the content area's h2 is the page's own heading). */
export function Card({
  title,
  aside,
  children,
  className = '',
  labelledBy,
}: {
  title?: ReactNode;
  aside?: ReactNode;
  children: ReactNode;
  className?: string;
  labelledBy?: string;
}) {
  return (
    <Surface tier="grouped" as="section" className={className} aria-labelledby={labelledBy}>
      {(title || aside) && (
        <div className="mb-2 flex flex-wrap items-start justify-between gap-2">
          {title && (
            <h3 id={labelledBy} className={`text-[13px] font-semibold ${INK}`}>
              {title}
            </h3>
          )}
          {aside}
        </div>
      )}
      {children}
    </Surface>
  );
}

/** The endpoint-1 read's non-view answers, as one line of plain words. */
export type ReadFailure =
  | { kind: 'cluster-not-registered'; message: string }
  | { kind: 'refused'; code: CodeView; message: string }
  | { kind: 'unauthenticated' }
  | { kind: 'error'; message: string };

const REFUSAL_TITLE: Record<string, string> = {
  CLUSTER_ACCESS_NOT_CONFIGURED: "XamOps can't sign in to this cluster",
  CLUSTER_UNREACHABLE: "XamOps can't reach this cluster's API server",
  CONTROLLER_NOT_READY: 'The Workload autoscaler is off in this environment',
  NO_ACCOUNT_ACCESS: "You don't have access to this AWS account",
};

export function readFailureText(failure: ReadFailure): { title: string; detail: string } {
  switch (failure.kind) {
    case 'cluster-not-registered':
      return { title: "This cluster isn't connected to XamOps yet", detail: "Install the XamOps agent from the cluster's setup guide, then reload." };
    case 'unauthenticated':
      return { title: 'Your session has expired', detail: 'Sign in again to see automation.' };
    case 'refused': {
      const code = failure.code.kind === 'known' ? failure.code.code : null;
      return { title: (code && REFUSAL_TITLE[code]) ?? codeText(failure.code), detail: failure.message };
    }
    default:
      return { title: "Couldn't read the Workload autoscaler", detail: failure.message };
  }
}

/** The card a page shows in place of what needs endpoint 1, with Retry. */
export function ReadFailureCard({ failure, onRetry }: { failure: ReadFailure; onRetry: () => void }) {
  const text = readFailureText(failure);
  return (
    <section className={`${CARD} px-4 py-3`} data-read-failure={failure.kind} data-k8s-kit="">
      <p className={`flex items-center gap-1.5 text-[13px] font-semibold ${INK}`}>
        <Icon icon="mdi:information-outline" width={16} aria-hidden="true" className={`shrink-0 ${MUTED}`} />
        {text.title}
      </p>
      <p className={`mt-1 text-[12px] ${MUTED}`}>{text.detail}</p>
      <button type="button" onClick={onRetry} className={`mt-3 ${BUTTON}`}>
        Retry
      </button>
    </section>
  );
}
