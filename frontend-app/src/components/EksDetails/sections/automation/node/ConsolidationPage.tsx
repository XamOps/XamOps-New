/**
 * Automation › Consolidation (consolidation phase 1, plan items 7-8, decision 11): the automation
 * agent's look-only consolidation plan, priced by the CloudK8s cost engine.
 *
 * Which nodes XamOps could empty (no pods to move) or drain (its pods fit elsewhere, keeping 15%
 * headroom), why every other node can't be removed or is kept, who removes nodes on this cluster,
 * and what removing the planned nodes would save each month. Read-only: the one request is
 * `GET /api/eks/autoscaling/{cluster}/consolidation-plan`, and there are no controls but Retry.
 * Moves are EMPTY or DRAIN only (v1 invariant I0). Anything not reported is a dash, never a guess.
 */
import { useId, type ReactNode } from 'react';
import useConsolidationPlan from '../../../../../hooks/useConsolidationPlan';
import type {
  ConsolidationBlockedNode,
  ConsolidationKeptNode,
  ConsolidationMove,
  ConsolidationNodeRef,
  ConsolidationPlan,
  ConsolidationPlanResponse,
} from '../../../../../types/k8sAutomation';
import { DASH } from '../../../../../api/verticalRightsizerAdapter';
import { FeatureMark } from '../../../../CloudK8s/shared/status/FeatureMark';
import { StatusChip } from '../../../../CloudK8s/shared/status/StatusChip';
import { PageTemplate } from '../../../../CloudK8s/shared/PageTemplate';
import { MetricStrip, type MetricCell } from '../../../../CloudK8s/shared/MetricStrip';
import { EmptyState } from '../../../../CloudK8s/shared/EmptyState';
import { BUTTON, DIVIDE, FAINT, INK, MUTED, Notice, RULE } from '../automationUi';
import {
  actorText,
  blockerSummary,
  freshnessText,
  metricCells,
  nodeCostText,
  orDash,
  requestedText,
  shortNode,
} from './consolidationDerivations';

export interface ConsolidationPageProps {
  clusterName: string;
  accountId: string;
  region: string;
}

const INTRO =
  "Which nodes XamOps could empty or drain, why the others can't be, and what removing them would save. Look-only: nothing here changes the cluster.";

const TH = 'px-4 py-2 font-semibold';
const TD = 'px-4 py-2.5';
const TBODY = 'divide-y divide-[rgba(0,0,0,0.06)] dark:divide-[rgba(255,255,255,0.08)]';

/** A de-boxed page section: a rule above, an h3 title, no card. */
function Section({ title, children, id }: { title: string; children: ReactNode; id: string }) {
  const titleId = useId();
  return (
    <section aria-labelledby={titleId} className={`border-t ${RULE} pt-4`} data-consolidation-section={id}>
      <h3 id={titleId} className={`text-[13px] font-semibold ${INK}`}>{title}</h3>
      {children}
    </section>
  );
}

/** A node's row header: its short name, then its shape (instance type · capacity type · zone). */
function NodeHeader({ node }: { node: ConsolidationNodeRef }) {
  return (
    <th scope="row" className={`${TD} font-semibold ${INK}`} title={node.name}>
      {shortNode(node.name)}
      <span className={`block text-[11px] font-normal ${FAINT}`}>
        {node.fargate ? 'Fargate' : `${orDash(node.instanceType)} · ${orDash(node.capacityType)} · ${orDash(node.zone)}`}
      </span>
    </th>
  );
}

function Table({ caption, headers, children }: { caption: string; headers: string[]; children: ReactNode }) {
  return (
    <div className="mt-2 overflow-x-auto">
      <table className="w-full border-collapse text-left text-[12px]">
        <caption className="sr-only">{caption}</caption>
        <thead>
          <tr className={`border-b ${RULE} text-[11px] ${MUTED}`}>
            {headers.map((h) => (
              <th key={h} scope="col" className={TH}>{h}</th>
            ))}
          </tr>
        </thead>
        <tbody className={TBODY}>{children}</tbody>
      </table>
    </div>
  );
}

function MoveRow({ move, response }: { move: ConsolidationMove; response: ConsolidationPlanResponse }) {
  const cost = nodeCostText(response, move.node.name);
  return (
    <tr className="align-top" data-move={move.node.name} data-move-type={move.type}>
      <NodeHeader node={move.node} />
      <td className={`${TD} ${INK}`}>{orDash(move.node.nodeGroup)}</td>
      <td className={TD}>
        <StatusChip tone="good" icon="mdi:check-circle-outline" label={move.type === 'EMPTY' ? 'Empty' : 'Drain'} />
      </td>
      <td className={`${TD} ${INK}`}>
        {move.pods.length}
        {move.pods.length > 0 && (
          <ul className={`mt-0.5 text-[11px] ${MUTED}`}>
            {move.pods.map((p) => (
              <li key={p.pod} data-pod-move={p.pod}>{`${p.pod} → ${shortNode(p.to)}`}</li>
            ))}
          </ul>
        )}
      </td>
      <td className={`${TD} ${INK}`}>{requestedText(move.node)}</td>
      <td className={`${TD} ${INK}`} data-move-cost="">
        {cost.value}
        {cost.reason && <span className={`block text-[11px] ${FAINT}`}>{cost.reason}</span>}
      </td>
    </tr>
  );
}

function BlockedRow({ blocked }: { blocked: ConsolidationBlockedNode }) {
  const b = blockerSummary(blocked.blockers);
  return (
    <tr className="align-top" data-blocked={blocked.node.name}>
      <NodeHeader node={blocked.node} />
      <td className={TD}>
        <StatusChip tone="warn" icon="mdi:alert-outline" label={b.label} detail={b.detail} />
      </td>
      <td className={`${TD} ${INK}`}>
        {b.pods.length === 0 ? DASH : (
          <ul>
            {b.pods.map((p) => <li key={p}>{p}</li>)}
          </ul>
        )}
      </td>
    </tr>
  );
}

function KeptRow({ kept }: { kept: ConsolidationKeptNode }) {
  return (
    <tr className="align-top" data-kept={kept.node.name}>
      <NodeHeader node={kept.node} />
      <td className={`${TD} ${INK}`}>{kept.reason || DASH}</td>
    </tr>
  );
}

function PlanBody({ response, plan }: { response: ConsolidationPlanResponse; plan: ConsolidationPlan }) {
  const actor = actorText(plan.actor);
  const age = freshnessText(response);
  return (
    <>
      {response.freshness === 'STALE' && age && <Notice>{age}</Notice>}
      <Section title="Who removes nodes" id="actor">
        <dl className={`mt-1 divide-y ${DIVIDE}`}>
          <div className="grid grid-cols-[160px_minmax(0,1fr)] gap-3 py-2 text-[12px]" data-consolidation-actor={plan.actor.kind}>
            <dt className={`font-semibold ${MUTED}`}>Removes nodes</dt>
            <dd className={INK}>
              <StatusChip tone="info" icon="mdi:information-outline" label={actor.label} detail={actor.detail} />
              {plan.actor.evidence.length > 0 && (
                <ul className={`mt-1 text-[11px] ${FAINT}`}>
                  {plan.actor.evidence.map((e) => <li key={e}>{e}</li>)}
                </ul>
              )}
            </dd>
          </div>
          <div className="grid grid-cols-[160px_minmax(0,1fr)] gap-3 py-2 text-[12px]">
            <dt className={`font-semibold ${MUTED}`}>Plan generated</dt>
            <dd className={INK}>
              {plan.generatedAt || DASH}
              {response.freshness === 'CURRENT' && age && <span className={`block text-[11px] ${FAINT}`}>{age}</span>}
            </dd>
          </div>
        </dl>
      </Section>
      <Section title="Plan preview" id="moves">
        {plan.moves.length === 0 ? (
          <p className={`mt-1 text-[12px] ${MUTED}`} data-no-moves="">
            No consolidation opportunities: no node is empty or under the threshold with pods that fit elsewhere.
          </p>
        ) : (
          <Table caption="Plan preview: nodes XamOps could empty or drain" headers={['Node', 'Node group', 'Move', 'Pods moving', 'Requested', 'Monthly cost']}>
            {plan.moves.map((m) => <MoveRow key={m.node.name} move={m} response={response} />)}
          </Table>
        )}
      </Section>
      {plan.blocked.length > 0 && (
        <Section title="Blocked" id="blocked">
          <Table caption="Nodes that can't be removed, and why" headers={['Node', 'Why', 'Blocking pod']}>
            {plan.blocked.map((b) => <BlockedRow key={b.node.name} blocked={b} />)}
          </Table>
        </Section>
      )}
      {plan.kept.length > 0 && (
        <Section title="Kept" id="kept">
          <Table caption="Nodes kept, and why" headers={['Node', 'Why']}>
            {plan.kept.map((k) => <KeptRow key={k.node.name} kept={k} />)}
          </Table>
        </Section>
      )}
      <p className={`border-t ${RULE} pt-3 text-[11px] ${FAINT}`} data-consolidation-settings="">
        {`Underutilized below ${plan.settings.underutilizedThresholdPct}% of allocatable requests · keeps ${plan.settings.headroomPct}% headroom on receiving nodes · node-group minimum, scale-up delay and disruption budget are checked by engine v1, not here`}
      </p>
    </>
  );
}

function ReadFailure({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <p className={`flex flex-wrap items-center gap-3 text-[12px] ${INK}`} data-consolidation-failure="">
      <span>{`Couldn't read the consolidation plan: ${message}`}</span>
      <button type="button" onClick={onRetry} className={BUTTON}>Retry</button>
    </p>
  );
}

export function ConsolidationPage({ clusterName, accountId, region }: ConsolidationPageProps) {
  const { plan: read, retry } = useConsolidationPlan(clusterName, accountId, region);
  const response = read.kind === 'ok' ? read.data : null;
  const cells: MetricCell[] = metricCells(response);

  const hero = (
    <div className="flex flex-col gap-4">
      <FeatureMark feature="node" title="Consolidation" />
      <div data-consolidation-kpis="">
        <MetricStrip cells={cells} heroLead loading={read.kind === 'loading'} aria-label="Consolidation summary" />
      </div>
    </div>
  );

  let body: ReactNode;
  if (read.kind === 'loading') {
    body = <p className={`text-[12px] ${MUTED}`} aria-busy="true">Reading the consolidation plan…</p>;
  } else if (read.kind === 'error') {
    body = <ReadFailure message={read.message} onRetry={retry} />;
  } else if (read.data.freshness === 'UNREADABLE' || (read.data.freshness !== 'NO_PLAN' && !read.data.plan)) {
    body = <ReadFailure message="the automation agent's last plan couldn't be read." onRetry={retry} />;
  } else if (read.data.freshness === 'NO_PLAN' || !read.data.plan) {
    body = (
      <div data-consolidation-empty="">
        <EmptyState icon="mdi:clipboard-text-outline" message="No consolidation plan yet" />
        <p className={`-mt-6 text-center text-[12px] ${MUTED}`}>
          The automation agent reports one every cycle while node automation is on (full or scoped).
        </p>
      </div>
    );
  } else {
    body = <PlanBody response={read.data} plan={read.data.plan} />;
  }

  return (
    // data-k8s-kit: every colour on this page has its dark variant, so the legacy dark CSS leaves it
    // alone. Read-only: the only request is the GET of the plan; Retry reads it again.
    <div data-consolidation-page="" data-k8s-kit="">
      <PageTemplate title="Consolidation" width="table" intro={INTRO} hero={hero}>
        {body}
        <p className={`text-[11px] ${FAINT}`} data-consolidation-source="">
          Source: the automation agent&apos;s look-only plan (GET /api/eks/autoscaling/{'{cluster}'}/consolidation-plan); prices from
          the CloudK8s cost engine (AWS Price List / spot price history).
        </p>
      </PageTemplate>
    </div>
  );
}

export default ConsolidationPage;
