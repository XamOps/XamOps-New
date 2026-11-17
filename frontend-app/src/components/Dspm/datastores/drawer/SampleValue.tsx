import React from 'react';

/*
 * The only code that turns a sample value into UI (decision D1). Samples are shown raw for now; masking,
 * when it comes, changes `formatSampleForDisplay` and nothing else. React escapes the text, so a value
 * such as `<<Jane Doe>>` shows exactly as Matters returned it.
 */

/** The one place samples are formatted. Masking (later) changes this function only. */
export function formatSampleForDisplay(value: string): string { return value; }

export const SampleValue: React.FC<{ value: string }> = ({ value }) =>
  <span className="font-mono text-[11px] break-all">{formatSampleForDisplay(value)}</span>;

/** The first sample, then "+N" for the rest; "-" when there is none. */
export const SampleCell: React.FC<{ samples: string[] }> = ({ samples }) => samples.length === 0
  ? <span>-</span>
  : <span><SampleValue value={samples[0]} />{samples.length > 1 && <span className="ml-1.5 rounded bg-[rgba(0,0,0,0.06)] px-1.5 py-0.5 text-[10px] font-semibold tabular-nums text-[rgba(0,0,0,0.55)]">+{samples.length - 1}</span>}</span>;
