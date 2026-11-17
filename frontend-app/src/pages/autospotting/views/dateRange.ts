/** Whole UTC days for the Analytics and Activity range pickers. */
export const RANGE_OPTIONS = [
  { days: 7, label: '7 days' },
  { days: 30, label: '30 days' },
  { days: 92, label: '92 days' },
] as const;

const day = (d: Date): string => d.toISOString().slice(0, 10);

/** `from` and `to` (`YYYY-MM-DD`) of the last `days` UTC days, ending today. */
export function lastDays(days: number, now: Date = new Date()): { from: string; to: string } {
  const to = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()));
  const from = new Date(to.getTime() - (days - 1) * 86_400_000);
  return { from: day(from), to: day(to) };
}
