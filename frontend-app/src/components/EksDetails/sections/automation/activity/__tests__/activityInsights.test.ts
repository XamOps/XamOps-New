/**
 * The Overview's 7-day timeline lanes (feedTimeline), on the shapes the live xamops-test-1 feed
 * produced: an HPA and the Deployment it scales share a name in one namespace (team-b/php-apache), so
 * each lane must still say which one it is.
 */
import { describe, expect, it } from 'vitest';
import { adaptActivityEntry } from '../../../../../../api/automationActivityAdapter';
import type { ActivityEntryWire } from '../../../../../../types/automationActivity';
import { feedTimeline } from '../activityInsights';
import { HPA_CHANGE, REFUSED } from './activityFixtures';

const NOW = Date.now();
const at = (minutesAgo: number) => new Date(NOW - minutesAgo * 60_000).toISOString();
const entry = (wire: ActivityEntryWire, i: number) => adaptActivityEntry(wire, i);

describe('feedTimeline lanes', () => {
  it('tells an HPA from the workload of the same name: the workload keeps the name, the HPA says what it is', () => {
    const refused = { ...REFUSED, id: 7, at: at(10), target: { namespace: 'team-b', kind: 'Deployment', name: 'php-apache' } };
    const hpa = { ...HPA_CHANGE, id: 6, at: at(20) };
    const t = feedTimeline([entry(refused, 0), entry(hpa, 1)], NOW);
    expect(t.lanes.map((l) => [l.name, l.feature])).toEqual([
      ['php-apache', 'workload'],
      ['php-apache (HPA)', 'hpa'],
    ]);
  });

  it('tells two workloads of the same name apart by their namespace', () => {
    const a = { ...REFUSED, id: 7, at: at(10), target: { namespace: 'team-a', kind: 'Deployment', name: 'api' } };
    const b = { ...REFUSED, id: 8, at: at(20), target: { namespace: 'team-b', kind: 'Deployment', name: 'api' } };
    const t = feedTimeline([entry(a, 0), entry(b, 1)], NOW);
    expect(t.lanes.map((l) => l.name)).toEqual(['team-a/api', 'team-b/api']);
  });

  it('keeps a lone HPA lane under its plain name', () => {
    const t = feedTimeline([entry({ ...HPA_CHANGE, at: at(5) }, 0)], NOW);
    expect(t.lanes.map((l) => l.name)).toEqual(['php-apache']);
  });
});
