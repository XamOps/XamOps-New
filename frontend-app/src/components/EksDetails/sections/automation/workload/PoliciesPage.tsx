/**
 * Automation › Workload autoscaler › Policies (UI v3 plan §2 "Policies"; automation-v2 design §4.3;
 * glossary v2 §5). The rules XamOps uses today, read from endpoint 1 (one GET, no write):
 *  - the defaults in plain words: the setting most workloads use, labelled "From the engine";
 *  - each workload's rules, with a link to its detail, where the mode switch is;
 *  - "Left alone": the protections endpoint 1 shows holding a workload back today;
 *  - the values the engine fixes, marked "Fixed in XamOps".
 * Changing rules here isn't available yet, and the page says so; nothing on it sends a request.
 */
import { useId } from 'react';
import useAutomationRead from '../../../../../hooks/useAutomationRead';
import { DASH, modeText } from '../../../../../api/verticalRightsizerAdapter';
import type { WorkloadView } from '../../../../../types/verticalRightsizer';
import { NowBanner } from '../../../../CloudK8s/shared/status/NowBanner';
import { StatusChip } from '../../../../CloudK8s/shared/status/StatusChip';
import { FeatureMark } from '../../../../CloudK8s/shared/status/FeatureMark';
import { nowSentence, type NowSentence } from '../../../../CloudK8s/shared/status/nowSentence';
import {
  FIXED_RULES,
  applyMethodText,
  commonPolicy,
  headroomText,
  leftAloneRules,
  lookBackText,
  modeChip,
  percentileText,
  policySourceText,
  withParam,
  type Common,
} from '../automationDerivations';
import { Card, CARD, DIVIDE, FAINT, INK, InPageLink, MUTED, RULE, ReadFailureCard, Tag } from '../automationUi';

/** Exact copy, UI v3 plan §2. */
export const POLICIES_HEADER =
  "These are the rules XamOps uses today. Changing them here isn't available yet; the mode switch is on each workload.";

export interface PoliciesPageProps {
  clusterName: string;
  accountId: string;
  region: string;
  /** Workload autoscaler › Workloads; a workload's detail is this with `?workload=`. */
  workloadsHref: string;
}

function share<T>(c: Common<T> | null, total: number): string {
  return c ? `${c.count} of ${total} ${total === 1 ? 'workload' : 'workloads'}` : 'Not reported';
}

function DefaultRow({ term, value, note }: { term: string; value: string; note: string }) {
  return (
    <div className="grid grid-cols-[180px_minmax(0,1fr)] gap-3 py-2 text-[12px]">
      <dt className={`font-semibold ${MUTED}`}>{term}</dt>
      <dd className={INK}>
        {value}
        <span className={`ml-2 text-[11px] ${FAINT}`}>{note}</span>
      </dd>
    </div>
  );
}

function Defaults({ workloads }: { workloads: readonly WorkloadView[] }) {
  const d = commonPolicy(workloads);
  const titleId = useId();
  return (
    <Card title="What most workloads use" labelledBy={titleId} aside={<Tag icon="mdi:information-outline">From the engine</Tag>}>
      <p className={`text-[12px] ${MUTED}`}>
        The setting most workloads on this cluster use, as the engine reports it for each workload in endpoint 1.
      </p>
      <dl className={`mt-1 divide-y ${DIVIDE}`}>
        <DefaultRow term="Mode" value={d.mode ? modeText(d.mode.value) : DASH} note={share(d.mode, d.total)} />
        <DefaultRow term="CPU target" value={percentileText(d.percentile?.value)} note={share(d.percentile, d.total)} />
        <DefaultRow term="Headroom" value={headroomText(d.headroomPct?.value)} note={share(d.headroomPct, d.total)} />
        <DefaultRow term="Look-back window" value={d.window ? lookBackText(d.window.value) : DASH} note={share(d.window, d.total)} />
        <DefaultRow term="How changes are applied" value={applyMethodText(d.applyMethod?.value)} note={share(d.applyMethod, d.total)} />
        <DefaultRow term="Settings source" value={policySourceText(d.policySource?.value)} note={share(d.policySource, d.total)} />
      </dl>
    </Card>
  );
}

function RulesTable({ workloads, detailHref }: { workloads: readonly WorkloadView[]; detailHref: (w: WorkloadView) => string }) {
  const titleId = useId();
  return (
    <section className={CARD} aria-labelledby={titleId}>
      <div className="px-4 pt-3">
        <h3 id={titleId} className={`text-[13px] font-semibold ${INK}`}>Rules for each workload</h3>
        <p className={`mt-0.5 text-[12px] ${MUTED}`}>Source: Workload autoscaler (endpoint 1): each workload&apos;s policy and its settings source.</p>
      </div>
      <div className="mt-2 overflow-x-auto">
        <table className="w-full border-collapse text-left text-[12px]" aria-labelledby={titleId}>
          <thead>
            <tr className={`border-y ${RULE} text-[11px] ${MUTED}`}>
              <th scope="col" className="px-4 py-2 font-semibold">Workload</th>
              <th scope="col" className="px-4 py-2 font-semibold">Mode</th>
              <th scope="col" className="px-4 py-2 font-semibold">Headroom</th>
              <th scope="col" className="px-4 py-2 font-semibold">CPU target</th>
              <th scope="col" className="px-4 py-2 font-semibold">Look-back</th>
              <th scope="col" className="px-4 py-2 font-semibold">How changes are applied</th>
              <th scope="col" className="px-4 py-2 font-semibold">Settings source</th>
              <th scope="col" className="px-4 py-2 font-semibold"><span className="sr-only">Mode switch</span></th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[rgba(0,0,0,0.06)] dark:divide-[rgba(255,255,255,0.08)]">
            {workloads.map((w) => {
              const chip = modeChip(w.policy?.mode);
              return (
                <tr key={w.id} data-policy-row={w.id} className="align-top">
                  <th scope="row" className={`px-4 py-2.5 font-semibold ${INK}`}>
                    {w.ref.name}
                    <span className={`block text-[11px] font-normal ${FAINT}`}>{w.ref.kind} · {w.ref.namespace}</span>
                  </th>
                  <td className="px-4 py-2.5"><StatusChip tone={chip.tone} icon={chip.icon} label={chip.label} /></td>
                  <td className={`px-4 py-2.5 ${INK}`}>{headroomText(w.policy?.headroomPct)}</td>
                  <td className={`px-4 py-2.5 ${INK}`}>{percentileText(w.policy?.percentile ?? w.provenance?.percentile)}</td>
                  <td className={`px-4 py-2.5 ${INK}`}>{lookBackText(w.policy?.window)}</td>
                  <td className={`px-4 py-2.5 ${INK}`}>{applyMethodText(w.policy?.applyMethod)}</td>
                  <td className={`px-4 py-2.5 ${INK}`}>{policySourceText(w.provenance?.policySource)}</td>
                  <td className="whitespace-nowrap px-4 py-2.5">
                    <InPageLink to={detailHref(w)} aria-label={`Open the mode switch for ${w.ref.name}`}>Mode switch</InPageLink>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function LeftAlone({ workloads }: { workloads: readonly WorkloadView[] }) {
  const rules = leftAloneRules(workloads);
  return (
    <Card title="Left alone">
      <p className={`text-[12px] ${MUTED}`}>
        The protections that hold a workload on this cluster back today. Source: Workload autoscaler (endpoint 1): skip reasons and
        container reasons.
      </p>
      {rules.length === 0 ? (
        <p className={`mt-2 text-[12px] ${INK}`}>No protection holds a workload back on this cluster right now.</p>
      ) : (
        <ul className={`mt-2 divide-y ${DIVIDE}`}>
          {rules.map((rule) => (
            <li key={rule.id} className="py-2" data-left-alone={rule.id}>
              <div className="flex flex-wrap items-center gap-2">
                <StatusChip tone="off" icon="mdi:minus-circle-outline" label={rule.label} />
                <span className={`text-[12px] ${MUTED}`}>
                  {rule.workloads.length} {rule.workloads.length === 1 ? 'workload' : 'workloads'}: {rule.workloads.map((w) => w.ref.name).join(', ')}
                </span>
              </div>
              {rule.sentence && <p className={`mt-1 text-[12px] ${INK}`}>{rule.sentence}</p>}
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

function Fixed() {
  return (
    <Card title="Set by XamOps" aside={<Tag icon="mdi:lock-outline">Fixed in XamOps</Tag>}>
      <p className={`text-[12px] ${MUTED}`}>The engine uses these values for every workload. Endpoint 1 doesn&apos;t carry them yet.</p>
      <dl className={`mt-1 divide-y ${DIVIDE}`}>
        {FIXED_RULES.map((rule) => (
          <DefaultRow key={rule.label} term={rule.label} value={rule.value} note="Fixed in XamOps" />
        ))}
      </dl>
    </Card>
  );
}

export function PoliciesPage({ clusterName, accountId, region, workloadsHref }: PoliciesPageProps) {
  const { loading, result, reload } = useAutomationRead({ clusterName, accountId, region });
  const view = result?.kind === 'ok' ? result.view.vertical : null;
  const detailHref = (w: WorkloadView) => withParam(withParam(workloadsHref, 'workload', null), 'workload', w.id);

  let sentence: NowSentence;
  if (loading && !result) {
    sentence = { clauses: ['Reading the rules XamOps uses on this cluster…'], chips: [] };
  } else if (view) {
    const d = commonPolicy(view.workloads);
    sentence = nowSentence('policies', {
      workloads: view.workloads.length,
      commonMode: d.mode ? { mode: modeText(d.mode.value), count: d.mode.count } : undefined,
      ownRules: view.workloads.filter((w) => w.provenance?.policySource === 'WORKLOAD_ROW').length,
      automatic: view.workloads.filter((w) => w.policy?.mode === 'AUTO').length,
      leftAlone: new Set(leftAloneRules(view.workloads).flatMap((r) => r.workloads.map((w) => w.id))).size,
    });
  } else {
    sentence = nowSentence('policies', {});
  }

  return (
    // data-k8s-kit: every colour on this page has its dark variant, so the legacy dark CSS leaves it alone.
    <div className="space-y-4" data-policies-page="" data-k8s-kit="">
      <div className="space-y-1">
        <FeatureMark feature="workload" title="Workload autoscaler rules" />
        <p className={`text-[12px] ${MUTED}`} data-policies-header="">{POLICIES_HEADER}</p>
      </div>
      <NowBanner sentence={sentence} />
      {loading && !result && <div className="skeleton h-40 rounded-lg" />}
      {result && result.kind !== 'ok' && <ReadFailureCard failure={result} onRetry={reload} />}
      {view && (
        <>
          <Defaults workloads={view.workloads} />
          {view.workloads.length > 0 && <RulesTable workloads={view.workloads} detailHref={detailHref} />}
          <LeftAlone workloads={view.workloads} />
          <Fixed />
        </>
      )}
    </div>
  );
}

export default PoliciesPage;
