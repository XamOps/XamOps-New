/**
 * The Assists page's Secret Expiry tag (design 6.6 and 8): "Expired" once the expiry time is reached, and
 * "Expires in N days" while N, the days left rounded up, is 1 to 30.
 */

const DAY_SECONDS = 86_400;

/** The most days ahead an expiry still gets the "Expires in N days" tag. */
export const EXPIRY_WARNING_DAYS = 30;

export type SecretExpiry = { kind: 'expired' } | { kind: 'soon'; days: number } | null;

/** The tag for an expiry time (epoch seconds) at `nowMs`; null for no tag or no expiry. */
export function secretExpiry(epochSeconds: number | null, nowMs: number): SecretExpiry {
  if (epochSeconds === null || !Number.isFinite(epochSeconds)) return null;
  const secondsLeft = epochSeconds - nowMs / 1000;
  if (secondsLeft <= 0) return { kind: 'expired' };
  const days = Math.ceil(secondsLeft / DAY_SECONDS);
  return days <= EXPIRY_WARNING_DAYS ? { kind: 'soon', days } : null;
}

/** The tag's text: "Expired", "Expires in 1 day", "Expires in 12 days". */
export function secretExpiryText(expiry: Exclude<SecretExpiry, null>): string {
  if (expiry.kind === 'expired') return 'Expired';
  return `Expires in ${expiry.days} ${expiry.days === 1 ? 'day' : 'days'}`;
}
