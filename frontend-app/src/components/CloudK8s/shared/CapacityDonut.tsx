/**
 * One cluster resource as cast.ai's dashboard draws it (glossary §9.1; design §10.1): the provisioned
 * total in the centre of a ring split into requested, the rest of allocatable and overhead, with a
 * thinner inner ring for what is used. The rings are decorative; the legend beside them carries
 * every number, each term with the glossary's definition as its tooltip. Every colour is a series
 * token (src/styles/k8sSemanticTokens.css). Nothing unreported is drawn or counted as 0.
 */
import type { ReactNode } from 'react';
import type { ResourceCapacity } from '../../../utils/k8sDerivations';
import { DASH, formatPercent } from '../../../utils/k8sQuantities';

/** The glossary's definitions (§9.1), shown as each term's tooltip. */
export const CAPACITY_TERMS = {
  provisioned: 'CPU and memory of every node in the cluster, as the machine reports it.',
  allocatable: 'What Kubernetes can give to pods: provisioned minus what the node keeps for the system.',
  overhead: 'Provisioned minus allocatable: reserved for the operating system and Kubernetes itself.',
  requested: "The sum of the requests of running pods. Kubernetes reserves this, whether it's used or not.",
  used: 'What running containers actually consumed: CPU as a 5-minute rate, memory as working set.',
} as const;

const SIZE = 156;
const CENTRE = SIZE / 2;
const OUTER = { r: 70, width: 12 };
const INNER = { r: 55, width: 6 };

const ratio = (part: number | null, whole: number | null) => (part !== null && whole !== null && whole > 0 ? (part / whole) * 100 : null);

/** An arc from fraction `from` to `to` of the circle, starting at 12 o'clock and running clockwise. */
function Arc({ r, width, from, to, className }: { r: number; width: number; from: number; to: number; className: string }) {
  const c = 2 * Math.PI * r;
  const start = Math.max(0, Math.min(1, from));
  const end = Math.max(start, Math.min(1, to));
  if (end - start <= 0) return null;
  return (
    <circle
      cx={CENTRE} cy={CENTRE} r={r} fill="none" strokeWidth={width} className={className}
      strokeDasharray={`${(end - start) * c} ${c}`} strokeDashoffset={-start * c}
      transform={`rotate(-90 ${CENTRE} ${CENTRE})`}
    />
  );
}

function Row({ swatch, term, title, value, valueTitle, detail }: {
  swatch: string; term: string; title: string; value: string; valueTitle?: string; detail: ReactNode;
}) {
  return (
    <div className="grid grid-cols-[minmax(0,1fr)_auto] items-baseline gap-x-3 border-b border-[rgba(0,0,0,0.06)] py-2 last:border-b-0" data-capacity-row={term}>
      <dt className="flex items-center gap-2 text-[13px] font-semibold text-[rgba(0,0,0,0.88)]" title={title}>
        <span className={`h-2.5 w-2.5 shrink-0 rounded-sm ${swatch}`} aria-hidden="true" />
        {term}
      </dt>
      <dd className="text-right text-[13px] font-semibold tabular-nums text-[rgba(0,0,0,0.88)]" title={valueTitle}>{value}</dd>
      {detail && <dd className="col-span-2 text-right text-[12px] text-[rgba(0,0,0,0.6)]">{detail}</dd>}
    </div>
  );
}

export interface CapacityDonutProps {
  /** "CPU" or "Memory": names the rings for assistive tech. */
  resource: string;
  capacity: ResourceCapacity;
  /** Formats a total of this resource: "8.00 vCPU", "14.98 GiB". */
  format: (value: number | null) => string;
  /** Why used is "—": the source that didn't report it. */
  usedMissing: string;
}

export function CapacityDonut({ resource, capacity, format, usedMissing }: CapacityDonutProps) {
  const { provisioned, allocatable, overhead, requested, used } = capacity;
  // The ring is the provisioned total; without it, allocatable, and no overhead segment.
  const whole = provisioned ?? allocatable;
  const share = (v: number | null) => (v !== null && whole ? v / whole : 0);
  const usedDetail = used === null
    ? usedMissing
    : [ratio(used, allocatable) !== null && `${formatPercent(ratio(used, allocatable))} of allocatable`,
      ratio(used, requested) !== null && `${formatPercent(ratio(used, requested))} of requested`].filter(Boolean).join(' · ') || null;

  return (
    <div className="flex flex-wrap items-center gap-x-5 gap-y-4" data-capacity={resource}>
      <div className="relative shrink-0" style={{ width: SIZE, height: SIZE }}>
        <svg width={SIZE} height={SIZE} viewBox={`0 0 ${SIZE} ${SIZE}`} aria-hidden="true" focusable="false">
          <circle cx={CENTRE} cy={CENTRE} r={OUTER.r} fill="none" strokeWidth={OUTER.width} className="stroke-series-allocatable" />
          {provisioned !== null && allocatable !== null && (
            <Arc {...OUTER} from={share(allocatable)} to={1} className="stroke-series-overhead" />
          )}
          <Arc {...OUTER} from={0} to={share(requested)} className="stroke-series-requested" />
          <circle cx={CENTRE} cy={CENTRE} r={INNER.r} fill="none" strokeWidth={INNER.width} className="stroke-series-allocatable" />
          {used !== null && <Arc {...INNER} from={0} to={share(used)} className="stroke-series-used" />}
        </svg>
        <div className="absolute inset-0 flex flex-col items-center justify-center text-center">
          <span className="text-[15px] font-semibold tabular-nums text-[rgba(0,0,0,0.88)]" title={CAPACITY_TERMS.provisioned}>{format(whole)}</span>
          <span className="text-[11px] text-[rgba(0,0,0,0.6)]">{provisioned !== null ? 'provisioned' : 'allocatable'}</span>
        </div>
      </div>
      <dl className="min-w-[176px] flex-1">
        <Row
          swatch="bg-series-requested" term="Requested" title={CAPACITY_TERMS.requested} value={format(requested)}
          detail={ratio(requested, allocatable) !== null ? `${formatPercent(ratio(requested, allocatable))} of allocatable` : null}
        />
        <Row
          swatch="bg-series-used" term="Used" title={CAPACITY_TERMS.used}
          value={used === null ? DASH : format(used)} valueTitle={used === null ? usedMissing : undefined} detail={usedDetail}
        />
        <Row
          swatch="border border-[rgba(0,0,0,0.12)] bg-series-allocatable" term="Allocatable" title={CAPACITY_TERMS.allocatable}
          value={format(allocatable)} detail="what pods can use"
        />
        <Row
          swatch="bg-series-overhead" term="Overhead" title={CAPACITY_TERMS.overhead}
          value={format(overhead)} valueTitle={overhead === null ? 'Needs node capacity and allocatable from the XamOps agent' : undefined}
          detail="kept by the node for the system"
        />
      </dl>
    </div>
  );
}

export default CapacityDonut;
