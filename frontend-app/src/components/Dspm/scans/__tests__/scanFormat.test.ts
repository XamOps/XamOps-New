import { describe, expect, it } from 'vitest';
import type { ScanScheduleTiming } from '../../../../types/dspmScans';
import {
  durationText, failedTitle, frequencyText, lastRunText, prettifyCode, progressText, runStatusTone, scheduleName,
  targetsText,
} from '../scanFormat';
import { SAAS_PROVIDERS } from '../saasProviders';

const timing = (over: Partial<ScanScheduleTiming>): ScanScheduleTiming => ({
  type: 'recurring', triggerTime: 0, recurrenceType: null, repeatOn: [], dayOfMonth: null, endDate: null, timezone: null, ...over,
});

describe('scanFormat', () => {
  it('names an unnamed schedule "Untitled schedule"', () => {
    expect(scheduleName(null)).toBe('Untitled schedule');
    expect(scheduleName('  ')).toBe('Untitled schedule');
    expect(scheduleName('Nightly')).toBe('Nightly');
  });

  it('says "Not run yet" only for a schedule that never ran', () => {
    expect(lastRunText(null)).toBe('Not run yet');
    expect(lastRunText(1_700_000_000)).toBeNull();
  });

  it('prefixes an estimated target count with "Up to "', () => {
    expect(targetsText(40, false)).toBe('40');
    expect(targetsText(1_200, true)).toBe('Up to 1,200');
    expect(targetsText(null, true)).toBe('-');
  });

  it('describes the frequency', () => {
    expect(frequencyText(null)).toBeNull();
    expect(frequencyText(timing({ type: 'once' }))).toEqual({ label: 'One-time', detail: null });
    expect(frequencyText(timing({ recurrenceType: 'DAILY' }))).toEqual({ label: 'Recurring', detail: 'Every day' });
    expect(frequencyText(timing({ recurrenceType: 'WEEKLY', repeatOn: ['MONDAY', 'wed', 'FR'] })))
      .toEqual({ label: 'Recurring', detail: 'Every Mon, Wed, Fri' });
    expect(frequencyText(timing({ recurrenceType: 'WEEKLY' }))).toEqual({ label: 'Recurring', detail: null });
    expect(frequencyText(timing({ recurrenceType: 'MONTHLY', dayOfMonth: 5 }))).toEqual({ label: 'Recurring', detail: 'Day 5 of every month' });
    expect(frequencyText(timing({ recurrenceType: 'HOURLY' }))).toEqual({ label: 'Recurring', detail: null });
  });

  it('writes the progress, its phase unless completed, and the skipped count', () => {
    const base = { completedDatastores: 3, totalDatastores: 10, phase: 'FILE_DISCOVERY', phaseLabel: 'File Discovery', skippedCount: 0 };
    expect(progressText(base)).toEqual({ main: '3 / 10', phase: 'File Discovery', skipped: null });
    expect(progressText({ ...base, phase: 'COMPLETED', phaseLabel: 'Completed', skippedCount: 2 }))
      .toEqual({ main: '3 / 10', phase: null, skipped: '2 skipped' });
    expect(progressText({ ...base, completedDatastores: 0, totalDatastores: 0, phase: null, phaseLabel: null }).main).toBe('0 / 0');
  });

  it('titles a run with failures "<n> of <total> failed"', () => {
    expect(failedTitle({ statusCounts: { FAILED: 2, SUCCESS: 5 }, totalDatastores: 7 })).toBe('2 of 7 failed');
    expect(failedTitle({ statusCounts: { SUCCESS: 5 }, totalDatastores: 5 })).toBeUndefined();
  });

  it('formats durations and codes', () => {
    expect(durationText(null)).toBe('-');
    expect(durationText(35)).toBe('35s');
    expect(durationText(250)).toBe('4m 10s');
    expect(durationText(7_500)).toBe('2h 5m');
    expect(prettifyCode('STATIC')).toBe('Static');
    expect(prettifyCode('SELECTED_DATASTORES')).toBe('Selected Datastores');
    expect(prettifyCode(null)).toBeNull();
    expect(runStatusTone('FAILED')).toBe('error');
    expect(runStatusTone('NEW_CODE')).toBe('neutral');
  });

  it('lists the six SaaS apps in the console order, each with an icon literal', () => {
    expect(SAAS_PROVIDERS.map(p => p.label)).toEqual(['Microsoft 365', 'Google Workspace', 'Jira', 'Confluence', 'GitLab', 'Slack']);
    expect(SAAS_PROVIDERS.map(p => p.id)).toEqual(['MICROSOFT_365', 'GOOGLE_WORKSPACE', 'JIRA', 'CONFLUENCE', 'GITLAB', 'SLACK']);
    for (const provider of SAAS_PROVIDERS) expect(provider.icon).toMatch(/^mdi:[a-z0-9-]+$/);
  });
});
