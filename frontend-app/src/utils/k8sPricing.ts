/**
 * Reference rates for "Estimated" CloudK8s costs (docs/cloudk8s/ui-revamp-design.md D9).
 *
 * The only place in the CloudK8s code where these two numbers live; a test fails if either appears
 * anywhere else in the tree. Every Estimated figure's tooltip shows the method, both rates and
 * {@link RATE_PROVENANCE}, which claims nothing more because nothing more is known: 0.0416 is also
 * exactly the t3.medium hourly price, so it may not be a per-vCPU rate at all. A future pricing
 * engine replaces this module.
 */

/** USD per vCPU-hour. */
export const CPU_RATE_PER_VCPU_HOUR = 0.0416;

/** USD per GiB-hour of memory. */
export const MEMORY_RATE_PER_GIB_HOUR = 0.00456;

export const RATE_PROVENANCE = 'Origin not documented in code; not region- or Spot-aware';

/** The tooltip line that names both rates, e.g. "$0.0416 per vCPU-hour · $0.00456 per GiB-hour". */
export const RATES_TEXT = `$${CPU_RATE_PER_VCPU_HOUR} per vCPU-hour · $${MEMORY_RATE_PER_GIB_HOUR} per GiB-hour`;

/** Cost of `cpuCores` and `memGiB` held for `hours`, at the reference rates. */
export function estimateCost(cpuCores: number, memGiB: number, hours: number): number {
  return (cpuCores * CPU_RATE_PER_VCPU_HOUR + memGiB * MEMORY_RATE_PER_GIB_HOUR) * hours;
}
