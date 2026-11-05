/**
 * PreflightList (FEAT-002): renders a `PreflightResult` — one row per check with a StatusMark
 * reflecting its result, the check title and detail, and a named remediation (text + an optional
 * command in a code block) in the ReasonText/reasonCopy remedy style. The top-level blocking flag,
 * identityMode and nodeScaler are shown above the checks.
 *
 * Honesty rules (CLAUDE.md): any missing field renders the muted em-dash (DASH). Pure presentation
 * — all data arrives as props; no fetching here.
 */
import { DASH } from '../../../utils/k8sQuantities';
import { SECTION, LABEL, BODY, SECONDARY } from '../shared/typeScale';
import { StatusMark, type StatusState } from '../shared/StatusMark';
import type { PreflightResult, PreflightCheck } from '../../../hooks/useK8sOnboarding';

export interface PreflightListProps {
  result: PreflightResult;
  className?: string;
}

/** Map a preflight result code to the StatusMark state + label. */
const RESULT_STATE: Record<PreflightCheck['result'], { state: StatusState; label: string }> = {
  PASS: { state: 'healthy', label: 'Pass' },
  WARN: { state: 'attention', label: 'Warning' },
  FAIL: { state: 'error', label: 'Fail' },
  SKIPPED: { state: 'not-set-up', label: 'Skipped' },
  UNKNOWN: { state: 'unknown', label: 'Unknown' },
};

function Field({ label, value }: { label: string; value: string | null | undefined }) {
  return (
    <div className="flex items-baseline gap-2">
      <span className={LABEL}>{label}</span>
      <span className={BODY}>{value ? value : <span className="text-[color:var(--chrome-muted)]">{DASH}</span>}</span>
    </div>
  );
}

/** A named remedy for a failing check: its text plus an optional command in a code block. */
function Remediation({ remediation }: { remediation: NonNullable<PreflightCheck['remediation']> }) {
  return (
    <div className="mt-1 flex flex-col gap-1" data-remediation="">
      <span className={`${SECONDARY} leading-[1.45]`}>{remediation.text}</span>
      {remediation.command ? (
        <pre className="overflow-x-auto rounded-[6px] border border-[color:var(--chrome-line)] bg-[rgba(0,0,0,0.04)] px-3 py-2 text-[12px] font-mono text-[color:var(--chrome-ink)] dark:bg-[rgba(255,255,255,0.06)]">
          <code>{remediation.command}</code>
        </pre>
      ) : null}
    </div>
  );
}

export function PreflightList({ result, className = '' }: PreflightListProps) {
  return (
    <div className={`flex flex-col gap-5 ${className}`.trim()} data-preflight-list="">
      <section className="flex flex-col gap-2" data-preflight-meta="">
        <div className="flex items-baseline gap-2">
          <span className={LABEL}>Blocking</span>
          <StatusMark
            state={result.blocking ? 'error' : 'healthy'}
            label={result.blocking ? 'Blocked' : 'Ready'}
          />
        </div>
        <Field label="Identity mode" value={result.identityMode} />
        <Field label="Node scaler" value={result.nodeScaler} />
      </section>

      <section className="flex flex-col gap-3" data-preflight-checks="">
        <h3 className={SECTION}>Checks</h3>
        <ul className="flex flex-col gap-3">
          {result.checks.map((check) => {
            const mark = RESULT_STATE[check.result];
            return (
              <li key={check.id} className="flex flex-col gap-1" data-check-row={check.id} data-check-result={check.result}>
                <div className="flex items-center justify-between gap-3">
                  <span className={BODY}>{check.title}</span>
                  <StatusMark state={mark.state} label={mark.label} />
                </div>
                {check.detail ? <span className={`${SECONDARY} leading-[1.45]`}>{check.detail}</span> : null}
                {check.remediation ? <Remediation remediation={check.remediation} /> : null}
              </li>
            );
          })}
        </ul>
      </section>
    </div>
  );
}

export default PreflightList;
