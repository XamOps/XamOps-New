/**
 * The reason a gated control is blocked (docs/cloudk8s/ui-revamp-design.md §6.1, §11.1, §14).
 *
 * The description element is always in the DOM with the given id, so every `aria-describedby`
 * resolves. `inline` shows it under the control with its remedy; `popover` keeps it visually hidden
 * (still in the accessibility tree) and adds a lock button, its own tab stop, labelled
 * "Why is <label> unavailable?", that opens a popover with the same text and the remedy visible.
 */
import { useId, useState } from 'react';
import { Icon } from '@iconify/react';
import { Link, useLocation } from 'react-router-dom';
import { Button, Dialog, DialogTrigger, Popover } from 'react-aria-components';
import type { Capability } from '../../../types/k8sAutomation';
import { useOptionalK8sCapabilities } from '../../../hooks/useK8sCapabilities';
import {
  NOT_READY_EXPLAINER,
  READ_WRITE_EXPLAINER,
  alsoNeedsLine,
  reasonCopy,
  type Remedy,
  type RemedyTarget,
  type ReasonCopyOptions,
} from './reasonCopy';

export type ReasonPlacement = 'inline' | 'popover';

/** Shown while capabilities load: the control is blocked, but there is no reason yet. */
export const CHECKING_ACCESS_TEXT = 'Checking automation access…';

/** Decorative lock that marks a blocked control (§14). */
export function LockIcon({ className = '' }: { className?: string }) {
  return (
    <Icon
      icon="mdi:lock-outline"
      width={14}
      aria-hidden="true"
      data-gated-lock=""
      className={`shrink-0 text-[rgba(0,0,0,0.6)] ${className}`}
    />
  );
}

const linkClass =
  'font-semibold text-[rgba(0,0,0,0.88)] underline underline-offset-2 rounded-sm '
  + 'outline-none focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.88)] focus-visible:ring-offset-2';

function useRemedyHref(target: RemedyTarget): { pathname?: string; search?: string; hash?: string } | string {
  const location = useLocation();
  if (target.kind === 'route') return target.to;
  const params = new URLSearchParams(location.search);
  params.set('tab', target.tab);
  params.set('sub', target.sub);
  return { pathname: location.pathname, search: `?${params.toString()}`, hash: `#${target.anchor}` };
}

function RemedyLink({ remedy }: { remedy: Extract<Remedy, { kind: 'link' }> }) {
  const to = useRemedyHref(remedy.target);
  return <Link to={to} className={linkClass}>{remedy.label}</Link>;
}

function Explainer({ remedy }: { remedy: Extract<Remedy, { kind: 'explainer' }> }) {
  const [open, setOpen] = useState(false);
  const panelId = useId();
  return (
    <span className="block">
      <button
        type="button"
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpen((v) => !v)}
        className={`${linkClass} bg-transparent p-0 text-[12px]`}
      >
        {remedy.label}
      </button>
      <span id={panelId} hidden={!open} className="mt-1 block text-[12px] text-[rgba(0,0,0,0.6)]">
        {remedy.explainer === 'read-write' ? READ_WRITE_EXPLAINER : NOT_READY_EXPLAINER}
        {remedy.explainer === 'read-write' && (
          <span className="mt-1 flex flex-wrap gap-3">
            <Link to="/tickets" className={linkClass}>Contact support</Link>
            <Link to="/account-manager" className={linkClass}>Open Account Manager</Link>
          </span>
        )}
      </span>
    </span>
  );
}

function RetryButton({ label }: { label: string }) {
  const caps = useOptionalK8sCapabilities();
  if (!caps) return null;
  return (
    <button type="button" onClick={caps.refetch} className={`${linkClass} bg-transparent p-0 text-[12px]`}>
      {label}
    </button>
  );
}

export function Remedies({ remedies }: { remedies: Remedy[] }) {
  if (!remedies.length) return null;
  return (
    <span className="mt-1 flex flex-col gap-1 text-[12px]">
      {remedies.map((remedy) => {
        switch (remedy.kind) {
          case 'link':
            return <RemedyLink key={remedy.label} remedy={remedy} />;
          case 'text':
            return <span key={remedy.label} className="text-[rgba(0,0,0,0.6)]">{remedy.label}</span>;
          case 'explainer':
            return <Explainer key={remedy.label} remedy={remedy} />;
          case 'retry':
            return <RetryButton key={remedy.label} label={remedy.label} />;
          default:
            return null;
        }
      })}
    </span>
  );
}

export interface ReasonTextProps {
  /** The element id controls point `aria-describedby` at. */
  id: string;
  capability: Capability;
  /** The control's label: names the popover lock button. */
  label: string;
  placement?: ReasonPlacement;
  options?: ReasonCopyOptions;
  /** Overrides the gate text, e.g. "Saving…" while a switch saves. */
  override?: string;
  className?: string;
}

export function ReasonText({ id, capability, label, placement = 'inline', options, override, className = '' }: ReasonTextProps) {
  const gate = capability.gate;
  const copy = gate ? reasonCopy(gate, options) : null;
  const text = override ?? (copy ? copy.text : CHECKING_ACCESS_TEXT);
  const also = override || !gate ? null : alsoNeedsLine(capability.also);
  const heading = override ? undefined : copy?.heading;
  const remedies = override || !copy ? [] : copy.remedies;

  const description = (
    <>
      {heading && <span className="font-semibold text-[rgba(0,0,0,0.88)]">{heading}: </span>}
      <span>{text}</span>
      {also && <span className="block">{also}</span>}
    </>
  );

  if (placement === 'popover') {
    return (
      <span className={`inline-flex items-center ${className}`}>
        <span id={id} className="sr-only" title={gate?.raw}>{description}</span>
        <DialogTrigger>
          <Button
            aria-label={`Why is ${label} unavailable?`}
            className="inline-flex h-6 w-6 items-center justify-center rounded-md text-[rgba(0,0,0,0.6)] outline-none
              hover:bg-[rgba(0,0,0,0.05)] focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.88)] focus-visible:ring-offset-2"
          >
            <Icon icon="mdi:lock-outline" width={14} aria-hidden="true" />
          </Button>
          <Popover
            placement="bottom start"
            className="max-w-[320px] rounded-lg border border-[rgba(0,0,0,0.12)] bg-white p-3 text-[12px] text-[rgba(0,0,0,0.6)] shadow-[0_4px_16px_rgba(0,0,0,0.12)]"
          >
            <Dialog aria-label={`Why ${label} is unavailable`} className="outline-none">
              <span className="block text-[rgba(0,0,0,0.88)]">{description}</span>
              <Remedies remedies={remedies} />
            </Dialog>
          </Popover>
        </DialogTrigger>
      </span>
    );
  }

  return (
    <span className={`mt-1 block text-[12px] leading-[1.45] text-[rgba(0,0,0,0.6)] ${className}`}>
      <span id={id} className="block" title={gate?.raw}>{description}</span>
      <Remedies remedies={remedies} />
    </span>
  );
}

export default ReasonText;
