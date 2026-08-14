/**
 * Minimal promise concurrency limiter.
 *
 * Why this exists: the Observability metrics dashboard renders 18 independent panels,
 * each of which fires its own /api/observability/metrics/query on mount and again on
 * every 30s refresh tick. Browsers happily open all of them at once, which means a
 * single page load lands ~19 simultaneous requests on xamops-service. Each one occupies
 * a Tomcat worker thread and briefly a JDBC connection while the org ID is resolved,
 * so the burst alone could exhaust the 20-connection Hikari pool and produce
 * "Unable to acquire JDBC Connection" on unrelated endpoints.
 *
 * Limiting to a handful in flight turns the burst into a short queue. Total wall-clock
 * time for the dashboard is barely affected because the backend was the bottleneck
 * anyway, but the backend stops falling over.
 */
export function createLimiter(maxConcurrent: number) {
  let active = 0;
  const queue: (() => void)[] = [];

  const next = () => {
    if (active >= maxConcurrent) return;
    const run = queue.shift();
    if (!run) return;
    active++;
    run();
  };

  return function limit<T>(task: () => Promise<T>): Promise<T> {
    return new Promise<T>((resolve, reject) => {
      queue.push(() => {
        task()
          .then(resolve, reject)
          .finally(() => {
            active--;
            next();
          });
      });
      next();
    });
  };
}

/**
 * Shared across every useObservabilityData consumer, so the cap applies to the whole
 * page rather than per hook instance. 6 matches the browser's practical per-host
 * HTTP/1.1 limit, so nothing is left idle.
 */
export const observabilityQueryLimit = createLimiter(6);
