import React from 'react';
import { maskSample } from '../../../../utils/sampleMask';
/*
 * The only code that turns a sample value into UI (decision D1). Samples arrive masked from xamops-service
 * (build 9: half mask, nobody sees a raw value). `formatSampleForDisplay` applies the same rule again as a
 * second layer; the rule is idempotent, so a server-masked value renders unchanged. React escapes the
 * text, so a value such as `<<Ja•• D••>>` shows exactly as it arrived.
 */
/** The one place samples are formatted: the half mask of `utils/sampleMask`. */
export function formatSampleForDisplay(value: string): string { return maskSample(value); }
export const SampleValue: React.FC<{ value: string }> = ({ value }) =>
  <span className="font-mono text-[11px] break-all">{formatSampleForDisplay(value)}</span>;
/** The first sample, then "+N" for the rest; "-" when there is none. */
export const SampleCell: React.FC<{ samples: string[] }> = ({ samples }) => samples.length === 0
  ? <span>-</span>
  : <span><SampleValue value={samples[0]} />{samples.length > 1 && <span className="ml-1.5 rounded bg-[rgba(0,0,0,0.06)] px-1.5 py-0.5 text-[10px] font-semibold tabular-nums text-[rgba(0,0,0,0.55)]">+{samples.length - 1}</span>}</span>;
