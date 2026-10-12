/**
 * A requested-of-allocatable value with a % bar, as cast.ai's node list shows requests
 * (docs/cloudk8s/ui-revamp-design.md §10.5, §14). The text carries the value; the bar is decorative.
 * Unknown allocatable shows the requested value alone, without a bar or a percentage. The bar's
 * colours are the requested and allocatable series tokens (src/styles/k8sSemanticTokens.css).
 */
import { formatPercent } from '../../../utils/k8sQuantities';

export interface RequestBarProps {
  /** "1.2 cores", "2.1 GiB". */
  requested: string;
  /** "3.92 cores"; null when the node doesn't report it. */
  allocatable: string | null;
  pct: number | null;
}

export function RequestBar({ requested, allocatable, pct }: RequestBarProps) {
  return (
    <span className="flex min-w-[120px] flex-col gap-1" data-request-bar="">
      <span className="flex items-baseline justify-between gap-2 tabular-nums">
        <span>{allocatable ? `${requested} of ${allocatable}` : requested}</span>
        {pct !== null && <span className="text-[12px] text-[rgba(0,0,0,0.6)]">{formatPercent(pct)}</span>}
      </span>
      {pct !== null && (
        <span className="h-1.5 overflow-hidden rounded-full bg-series-allocatable" aria-hidden="true">
          <span className="block h-full rounded-full bg-series-requested" style={{ width: `${Math.min(100, Math.max(0, pct))}%` }} />
        </span>
      )}
    </span>
  );
}

export default RequestBar;
