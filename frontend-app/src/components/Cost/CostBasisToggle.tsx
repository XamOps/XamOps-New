import React, { useEffect, useId, useRef, useState } from 'react';
import { Icon } from '@iconify/react';
import type { CostBasis, ReportCapabilities } from '../../api/curInsightsApi';

/**
 * Which cost the card's figures are computed from.
 *
 * <p>Two buttons with `aria-pressed` inside a labelled group rather than the segmented
 * `AriaTabGroup` used for the card's sub-tabs: this is a filter over one view's figures, not
 * navigation between views, and the existing control is a `tablist`.
 *
 * <p>Defaults to unblended and never changes on its own. Switching basis is a reader's decision —
 * a card that silently amortized would change every figure on the page relative to the Cost
 * Breakdown widget below it.
 */

/**
 * Overridable wording, merged `{...AWS_COPY, ...copy}` like every other control on this card.
 *
 * <p>Exported from this file, as every other copy contract on this card is exported from the
 * component that owns it. No provider overrides it today — the toggle renders only when a
 * capabilities fetcher is supplied, and only AWS supplies one — so this exists to satisfy the copy
 * rule and to give the sentences one place to be read and reviewed.
 *
 * <p>There is deliberately no field for the "this report cannot do it" sentence: that one is
 * `capabilities.amortizedDetail`, which the backend writes because only the backend knows which
 * column is missing. A client-side override would have to discard it or wrap it, and wrapping a
 * sentence that already ends in a full stop is how copy reads like two spliced together.
 */
export interface CostBasisToggleCopy {
  groupLabel?: string;
  unblendedLabel?: string;
  amortizedLabel?: string;
  /** While the capability probe is in flight. */
  checkingDetail?: string;
  /** When the probe itself failed, which is a different fact from a report that cannot answer. */
  unknownDetail?: string;
  /** The popover body: what the two bases are and why a reader wants each. */
  explanation?: React.ReactNode;
}

const AWS_COPY: Required<CostBasisToggleCopy> = {
  groupLabel: 'Cost basis',
  unblendedLabel: 'Unblended',
  amortizedLabel: 'Amortized',
  checkingDetail: 'Checking what this report can be asked…',
  unknownDetail:
    'We could not check what this report can be asked. Amortized is unavailable until that'
    + ' succeeds.',
  explanation: (
    <>
      <strong>Unblended</strong> is what AWS charged on the day — a reservation or Savings Plan you
      paid for upfront lands whole on the day you bought it, so that month looks enormous and the
      rest of the term looks cheap. <strong>Amortized</strong> spreads that payment across the term
      and prices covered usage at the rate you actually paid. Use unblended to match your invoice;
      use amortized to compare one month against another, or to see what a workload really costs to
      run.
    </>
  ),
};

interface CostBasisToggleProps {
  value: CostBasis;
  onChange: (basis: CostBasis) => void;
  /**
   * What the report can be asked.
   *
   * <p>`undefined` means the probe is still in flight; `null` means the probe itself failed. Those
   * are different facts and the toggle says a different sentence for each — see `probeState`.
   */
  capabilities?: ReportCapabilities | null;
  copy?: CostBasisToggleCopy;
}

const CostBasisToggle: React.FC<CostBasisToggleProps> = ({
  value,
  onChange,
  capabilities,
  copy,
}) => {
  const text = { ...AWS_COPY, ...copy };
  const descriptionId = useId();
  const popoverId = useId();
  const [explaining, setExplaining] = useState(false);
  const explainTrigger = useRef<HTMLButtonElement>(null);

  /*
   * Gated on the positive, not on the negative.
   *
   * `useCurInsight` returns undefined while pending and the fetcher returns null on failure, and
   * neither is `false` — so a `capabilities?.amortizedAvailable === false` test would render the
   * button *enabled* on every page load and after every probe failure, with nothing in
   * aria-describedby. A click inside that window issues an amortized query that 422s and takes the
   * card to its basis-failed state, which is the working-looking control this exists to rule out.
   */
  const probeState =
    capabilities === undefined
      ? 'checking'
      : capabilities === null
        ? 'unknown'
        : capabilities.amortizedAvailable
          ? 'available'
          : 'unavailable';

  const description =
    probeState === 'checking'
      ? text.checkingDetail
      : probeState === 'unknown'
        ? text.unknownDetail
        : probeState === 'unavailable'
          ? capabilities?.amortizedDetail || text.unknownDetail
          : '';

  // Escape closes the explanation and hands focus back, so a keyboard reader is not stranded.
  useEffect(() => {
    if (!explaining) return undefined;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setExplaining(false);
        explainTrigger.current?.focus();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [explaining]);

  const option = (basis: CostBasis, label: string, blocked: boolean) => {
    const active = value === basis;
    return (
      <button
        type="button"
        /*
         * aria-disabled, never the HTML `disabled` attribute.
         *
         * `disabled` removes the button from the tab order, so the aria-describedby sentence
         * explaining *why* it is unavailable is never announced on focus — which would defeat the
         * point of writing three different sentences. The onClick returns early instead, so there
         * is no path to an amortized query.
         */
        aria-pressed={active}
        aria-disabled={blocked || undefined}
        aria-describedby={blocked && description ? descriptionId : undefined}
        onClick={() => {
          if (blocked || active) return;
          onChange(basis);
        }}
        className={`px-2.5 py-1 text-[12px] font-medium rounded-md transition-colors ${
          active ? 'bg-white text-black shadow-sm' : 'text-[rgba(0,0,0,0.55)] hover:text-black'
        } ${blocked ? 'opacity-40' : ''}`}
      >
        {label}
      </button>
    );
  };

  return (
    <div className="no-print relative flex items-center gap-1.5">
      <div
        role="group"
        aria-label={text.groupLabel}
        className="inline-flex items-center gap-0.5 p-0.5 bg-[rgba(0,0,0,0.04)] rounded-lg"
      >
        {option('unblended', text.unblendedLabel, false)}
        {option('amortized', text.amortizedLabel, probeState !== 'available')}
      </div>

      <button
        type="button"
        ref={explainTrigger}
        onClick={() => setExplaining((open) => !open)}
        aria-expanded={explaining}
        aria-controls={popoverId}
        aria-label="What do unblended and amortized mean?"
        className="w-6 h-6 inline-flex items-center justify-center rounded text-[rgba(0,0,0,0.35)] hover:text-[rgba(0,0,0,0.7)] hover:bg-[rgba(0,0,0,0.05)] transition-colors"
      >
        <Icon icon="mdi:information-outline" width={15} />
      </button>

      {/* Rendered when blocked whatever the reason, because an option that cannot be chosen and
          does not say why is indistinguishable from one that is broken. */}
      {description && (
        <p
          id={descriptionId}
          /*
           * Anchored left, not right. This toggle is the first child of the filter row, so its
           * container sits against the card's left edge and is only as wide as the button group
           * plus the info icon — roughly 160px. A 280px box pinned by its right edge therefore
           * started about 120px to the LEFT of the card and ran off the viewport, which is where
           * the "Demo data is unblended only…" sentence was disappearing to.
           */
          className="absolute top-full left-0 mt-1 w-[280px] max-w-[calc(100vw-2rem)] text-[10.5px] text-[rgba(0,0,0,0.45)] leading-relaxed text-left"
        >
          {description}
        </p>
      )}

      <div
        id={popoverId}
        hidden={!explaining}
        className="absolute top-full right-0 mt-2 z-20 w-[340px] p-3.5 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-lg text-[11.5px] text-[rgba(0,0,0,0.65)] leading-relaxed"
      >
        {text.explanation}
      </div>
    </div>
  );
};

export default CostBasisToggle;
