import { describe, it, expect, beforeEach } from 'vitest';
import { resolveDemoResponse } from './demoRouter';
import { DEMO_MODE_KEY, DEMO_ROLE_KEY } from './demoConfig';
import * as soc2 from './soc2MockData';

const readDemoBody = async (url: string) => {
  const response = resolveDemoResponse(url);
  expect(response, `expected a demo response for ${url}`).not.toBeNull();
  return JSON.parse(await response!.text());
};

describe('demo router — SOC 2 Program (ROLE_XAMOPS)', () => {
  beforeEach(() => {
    sessionStorage.setItem(DEMO_MODE_KEY, 'true');
    sessionStorage.setItem(DEMO_ROLE_KEY, 'ROLE_XAMOPS');
  });

  // useSoc2ProgramData loads these in one Promise.all — a single miss rejects the
  // lot and blanks the Program page along with every detail page beneath it.
  it.each([
    ['overview',     '/api/soc2/overview',     'object'],
    ['frameworks',   '/api/soc2/frameworks',   'array'],
    ['controls',     '/api/soc2/controls',     'array'],
    ['policies',     '/api/soc2/policies',     'array'],
    ['tasks',        '/api/soc2/tasks',        'array'],
    ['personnel',    '/api/soc2/personnel',    'array'],
    ['vendors',      '/api/soc2/vendors',      'array'],
    ['risks',        '/api/soc2/risks',        'array'],
    ['cloud tests',  '/api/soc2/cloud-tests',  'array'],
    ['integrations', '/api/soc2/integrations', 'array'],
    ['documents',    '/api/soc2/documents',    'array'],
  ])('serves populated demo data for %s', async (_label, url, shape) => {
    const body = await readDemoBody(url);
    if (shape === 'array') {
      expect(Array.isArray(body)).toBe(true);
      expect(body.length).toBeGreaterThan(0);
    } else {
      expect(Object.keys(body).length).toBeGreaterThan(0);
    }
  });

  it('answers policy publish with a single policy, not the list', async () => {
    const body = await readDemoBody('/api/soc2/policies/pol-11/publish');
    expect(Array.isArray(body)).toBe(false);
    expect(body.id).toBeTruthy();
  });

  it('answers a task update with a single task', async () => {
    const body = await readDemoBody('/api/soc2/tasks/tsk-09');
    expect(Array.isArray(body)).toBe(false);
    expect(body.title).toBeTruthy();
  });
});

describe('SOC 2 demo dataset is self-consistent', () => {
  const { demoSoc2Overview: ov } = soc2;

  it('derives the overview counters from the lists behind them', () => {
    expect(ov.controlsTotal).toBe(soc2.demoSoc2Controls.length);
    expect(ov.controlsPassing + ov.controlsInProgress + ov.controlsNotStarted)
      .toBe(soc2.demoSoc2Controls.length);
    expect(ov.policiesTotal).toBe(soc2.demoSoc2Policies.length);
    expect(ov.tasksTotal).toBe(soc2.demoSoc2Tasks.length);
    expect(ov.peopleTotal).toBe(soc2.demoSoc2Personnel.length);
    expect(ov.vendorsTotal).toBe(soc2.demoSoc2Vendors.length);
    expect(ov.risksTotal).toBe(soc2.demoSoc2Risks.length);
  });

  it('uses the exact lowercase task statuses the evidence KPI strip buckets on', () => {
    const allowed = new Set(['done', 'in_progress', 'in_review', 'todo']);
    soc2.demoSoc2Tasks.forEach((t) => expect(allowed).toContain(t.status));
    // every bucket populated, so no KPI tile reads zero
    ['done', 'in_progress', 'in_review', 'todo'].forEach((s) =>
      expect(soc2.demoSoc2Tasks.some((t) => t.status === s)).toBe(true));
  });

  it('uses policy statuses the status chart recognises', () => {
    const allowed = new Set(['published', 'draft', 'needs_review', 'archived']);
    soc2.demoSoc2Policies.forEach((p) => expect(allowed).toContain(p.status));
  });

  it('files every document under one of the three rendered categories', () => {
    const allowed = new Set(['Governance', 'Security', 'People']);
    soc2.demoSoc2Documents.forEach((d) => expect(allowed).toContain(d.category));
  });

  it('tags cloud tests with the lowercase providers the tab groups by', () => {
    const allowed = new Set(['aws', 'gcp', 'azure']);
    soc2.demoSoc2CloudTests.forEach((t) => expect(allowed).toContain(t.provider));
    allowed.forEach((p) =>
      expect(soc2.demoSoc2CloudTests.some((t) => t.provider === p)).toBe(true));
  });

  it('gives the framework detail page controls to list', () => {
    expect(soc2.demoSoc2Frameworks.length).toBeGreaterThan(0);
    // the detail page builds its requirements table from control.requirements
    expect(soc2.demoSoc2Controls.every((c) => c.requirements.length > 0)).toBe(true);
  });
});
