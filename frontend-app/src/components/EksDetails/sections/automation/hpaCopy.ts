/**
 * Plain-language copy for the HPA decisions, HPA refusals and the VPA coexistence wording.
 *
 * Sibling of {@link ./verticalCopy.ts}. Every HpaReason the engine (FEAT-001) and the apply service
 * (FEAT-002) can emit has a buyer-facing sentence here; codes never appear as visible text, only in
 * titles as "Code: <CODE>" so support can match a screen to a log. Unknown codes render verbatim as
 * "Other: <raw>", which the contract allows so the backend's enums can grow without a frontend
 * change.
 *
 * Source of truth: HpaReason values pinned by FEAT-001 and the refusal mapping in FEAT-002 findings.
 */

import type { CodeView } from '../../../../types/verticalRightsizer';

/** HPA decision + refusal labels, keyed by HpaReason name (and the shared gate codes the HPA uses). */
export const HPA_CODE_LABELS: Record<string, string> = {
  // HPA decisions (recommend tier, FEAT-001)
  AT_MAX_UNMET_DEMAND: 'Pinned at the maximum',
  MAX_ABOVE_OBSERVED_PEAK: 'Maximum higher than needed',
  TARGET_COADJUSTED: 'Target adjusted with the request',
  TARGET_RETUNED: 'Target retuned',
  HPA_RECOMMENDED_NEW: 'An HPA would help',
  WITHIN_REPLICA_HYSTERESIS: 'Change too small',
  // HPA refusals / safety rules (FEAT-002)
  COOLDOWN: 'Changed recently',
  GITOPS_MANAGED: 'Managed by Argo CD or Flux',
  PDB_FLOOR_BINDS: 'Blocked by a disruption budget',
  USER_MAX_RESPECTED: 'Your maximum is kept',
  BELOW_OBSERVED_NEED: 'Would go below observed need',
  CLAMP_BOUND: 'Target hit its limit',
  VPA_MANAGED: 'Requests left to the Kubernetes VPA',
  HPA_NOT_READABLE: 'HPA not readable',
  VPA_NOT_READABLE: 'VPA not readable',
};

/** The one coexistence line for an Off-mode VPA (glossary): not a warning. */
export const VPA_OFF_TEXT = "None — Off doesn't change pods";

export const parseHpaCode = (raw: string | null | undefined): CodeView | null => {
  if (!raw) return null;
  const trimmed = raw.trim();
  if (!trimmed) return null;
  const code = trimmed.startsWith('K8S_') ? trimmed.slice('K8S_'.length) : trimmed;
  const label = HPA_CODE_LABELS[code];
  return label ? { kind: 'known', code, label } : { kind: 'unknown', raw: trimmed };
};

export const parseHpaCodes = (raw: string[] | null | undefined): CodeView[] =>
  (raw ?? []).map(parseHpaCode).filter((c): c is CodeView => c !== null);

/** The text for a code: the label when known, "Other: <raw>" when not. */
export const hpaCodeText = (code: CodeView): string =>
  code.kind === 'known' ? code.label : `Other: ${code.raw}`;

const hpaName = (name: string | null): string => (name ? `the HPA ${name}` : 'the HPA');

/**
 * One HpaReason as a sentence. `decision` carries the numbers so the sentence can name the move
 * (e.g. "raise the maximum to 8"); `formula` is woven in when present.
 */
export const hpaReasonSentence = (
  code: CodeView,
  ctx: { name: string | null; min?: number | null; max?: number | null; target?: number | null; formula?: string | null } = {
    name: null,
  },
): string => {
  if (code.kind !== 'known') return hpaCodeText(code);
  const toMax = ctx.max != null ? ` to ${ctx.max}` : '';
  const toTarget = ctx.target != null ? ` to ${ctx.target}%` : '';
  switch (code.code) {
    case 'AT_MAX_UNMET_DEMAND':
      return `${hpaName(ctx.name)} has been pinned at its maximum with CPU above target, so XamOps suggests raising the maximum${toMax}.`;
    case 'MAX_ABOVE_OBSERVED_PEAK':
      return `The maximum is far above what this workload has ever needed, so XamOps suggests lowering it${toMax} while keeping headroom above the observed peak.`;
    case 'TARGET_COADJUSTED':
      return `XamOps moved the CPU request, so the HPA's CPU target is adjusted${toTarget} to keep the same number of replicas.`;
    case 'TARGET_RETUNED':
      return `XamOps suggests retuning the CPU target${toTarget} to match recent usage.`;
    case 'HPA_RECOMMENDED_NEW':
      return 'This workload has no HPA but could use one. XamOps suggests a starting min/max and CPU target. XamOps never creates an HPA on its own.';
    case 'WITHIN_REPLICA_HYSTERESIS':
      return 'The change would be too small to be worth making, so XamOps leaves the bounds as they are.';
    case 'COOLDOWN':
      return 'The HPA was changed recently, so XamOps waits before suggesting another change.';
    case 'GITOPS_MANAGED':
      return 'This HPA is managed by Argo CD or Flux. Change it in Git so your source of truth stays correct.';
    case 'PDB_FLOOR_BINDS':
      return 'A PodDisruptionBudget needs more replicas than the proposed minimum, so the minimum is kept.';
    case 'USER_MAX_RESPECTED':
      return 'You set a maximum for this HPA, and XamOps keeps it rather than raising past it.';
    case 'BELOW_OBSERVED_NEED':
      return 'XamOps could not read enough history to be sure a lower maximum is safe, so it leaves the maximum alone.';
    case 'CLAMP_BOUND':
      return "The CPU target hit its allowed range (10–90%), or this HPA has no CPU metric to retune, so XamOps leaves the target alone.";
    case 'VPA_MANAGED':
      return "A Kubernetes VerticalPodAutoscaler owns this workload's requests, so XamOps leaves CPU and memory to it. The HPA's replica bounds can still be tuned.";
    case 'HPA_NOT_READABLE':
      return "XamOps couldn't read this HPA, so it won't change it.";
    case 'VPA_NOT_READABLE':
      return "XamOps isn't allowed to read VerticalPodAutoscaler objects in this cluster, so it leaves this workload's requests alone to be safe.";
    default:
      return hpaCodeText(code);
  }
};

/** The title for a reason chip: the sentence, then the code. */
export const hpaReasonTitle = (
  code: CodeView,
  ctx?: { name: string | null; min?: number | null; max?: number | null; target?: number | null; formula?: string | null },
): string => `${hpaReasonSentence(code, ctx)} Code: ${code.kind === 'known' ? code.code : code.raw}`;
