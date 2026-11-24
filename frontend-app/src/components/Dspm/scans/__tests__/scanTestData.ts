/**
 * Synthetic Scan Management answers for the scans tests, in the backend's DTO shapes. Every value is
 * invented. The test files mock `apiClient` themselves (`vi.mock` is per file) and route through
 * `answerWith`.
 */
import { expect } from 'vitest';
import { fireEvent } from '@testing-library/react';
import type { DspmPage } from '../../../../types/dspm';
import type {
  EndpointScanConfiguration, SaasChannel, SaasScanConfiguration, SaasScanUser, ScanRun, ScanRunDatastore,
  ScanRunDetail, ScanSchedule, ScanScope, ScanTarget,
} from '../../../../types/dspmScans';
import { CONSOLE_ONLY_TOOLTIP } from '../../datastores/ui/ConsoleOnly';

export const T0 = Date.UTC(2026, 10, 19, 9, 30) / 1000;

export function page<T>(rows: T[], over: Partial<DspmPage<T>> = {}): DspmPage<T> {
  return { data: rows, page: 1, pageSize: 10, total: rows.length, totalPages: 1, ...over };
}

export const schedule = (over: Partial<ScanSchedule> = {}): ScanSchedule => ({
  id: '0a0a0a0a0a0a0a0a0a0a0a01', name: null, platform: 'cloud', platformLabel: 'Cloud',
  coverage: 'SELECTED_DATASTORES', coverageLabel: 'Selected datastores', totalDatastores: 40, totalIsEstimate: false,
  state: 'ACTIVE', legacy: true,
  timing: { type: 'once', triggerTime: T0, recurrenceType: null, repeatOn: [], dayOfMonth: null, endDate: null, timezone: 'UTC' },
  lastRunAt: null, lastRunStatus: null, lastRunStatusLabel: null, nextRunAt: null,
  ...over,
});

export const targets = (n: number): ScanTarget[] => Array.from({ length: n }, (_, i) => ({
  id: `0b0b0b0b0b0b0b0b0b0b${String(i).padStart(4, '0')}`, name: `target-store-${i + 1}`, subtype: 'S3', subtypeLabel: 'S3',
}));

export const run = (n: number, over: Partial<ScanRun> = {}): ScanRun => ({
  id: `11111111-2222-4333-8444-5555555555${String(n).padStart(2, '0')}`, name: `Sample scan ${n}`,
  coverage: 'ALL_DATASTORES', coverageLabel: 'All datastores', platform: 'endpoint', platformLabel: 'Endpoint',
  trigger: 'IMMEDIATE', triggerLabel: 'Immediate', scheduledTime: T0 - n * 3600, startedAt: T0 - n * 3600,
  endedAt: T0 - n * 3600 + 125, durationSeconds: 125, status: 'SUCCESS', statusLabel: 'Success',
  phase: 'COMPLETED', phaseLabel: 'Completed', statusCounts: { SUCCESS: 1 }, completedDatastores: 1,
  totalDatastores: 1, skippedCount: 0,
  ...over,
});

export const runDetail = (r: ScanRun, over: Partial<ScanRunDetail> = {}): ScanRunDetail => ({
  run: r, scopeNames: ['Sample scope'], skippedExamples: [], accounts: [{ value: 'sample-account', count: 1 }], ...over,
});

export const runDatastore = (over: Partial<ScanRunDatastore> = {}): ScanRunDatastore => ({
  id: '0c0c0c0c0c0c0c0c0c0c0c01', name: 'sample-laptop-01', subtype: 'MAC', subtypeLabel: 'macOS',
  accountName: null, region: null, status: 'SUCCESS', statusLabel: 'Success', failureReason: null,
  ...over,
});

export const scope = (over: Partial<ScanScope> = {}): ScanScope => ({
  id: '0d0d0d0d0d0d0d0d0d0d0d01', name: 'Sample scope', platform: 'cloud', platformLabel: 'Cloud',
  scopeMode: 'STATIC', totalDatastores: 3, updatedAt: T0, ...over,
});

export const GOOGLE_ID = '0e0e0e0e0e0e0e0e0e0e0e01';
export const SLACK_ID = '0e0e0e0e0e0e0e0e0e0e0e02';

export const googleConfig = (over: Partial<SaasScanConfiguration> = {}): SaasScanConfiguration => ({
  integrationId: GOOGLE_ID, realtimeEnabled: false, backfillingEnabled: true, samplingPercentage: 100,
  provisioningComplete: true, lastSavedAt: T0, lastSavedBy: 'owner@example.com',
  scopes: [
    { driveType: 'GOOGLE_WORKSPACE', driveLabel: 'Google Drive', scopeType: 'SELECTED_USERS', totalActiveUsers: 31, supportedDomains: ['example.com', 'example.net'] },
    { driveType: 'GMAIL', driveLabel: 'Gmail', scopeType: 'ALL_USERS', totalActiveUsers: 31, supportedDomains: ['example.com'] },
  ],
  ...over,
});

export const slackConfig = (over: Partial<SaasScanConfiguration> = {}): SaasScanConfiguration => ({
  integrationId: SLACK_ID, realtimeEnabled: true, backfillingEnabled: false, samplingPercentage: null,
  provisioningComplete: false, lastSavedAt: null, lastSavedBy: null,
  scopes: [{ driveType: 'SLACK', driveLabel: 'Slack', scopeType: 'SELECTED_USERS', totalActiveUsers: 4, supportedDomains: [] }],
  ...over,
});

export const users = (n: number): SaasScanUser[] => Array.from({ length: n }, (_, i) => ({
  id: `0f0f0f0f0f0f0f0f0f0f${String(i).padStart(4, '0')}`, email: `person${i + 1}@example.com`,
  status: 'ACTIVE', statusLabel: 'Active', selected: i % 2 === 0,
}));

export const CHANNELS: SaasChannel[] = [
  { id: 'C0TEST01', name: 'team-room', channelType: 'PUBLIC', channelStatus: 'ACTIVE', memberCount: 12, externalShared: false, topic: 'Daily chatter', isNew: false, selected: true },
  { id: 'C0TEST02', name: 'board-prep', channelType: 'PRIVATE', channelStatus: 'ACTIVE', memberCount: 3, externalShared: true, topic: null, isNew: true, selected: false },
  { id: 'C0TEST03', name: 'retired-project', channelType: 'PUBLIC', channelStatus: 'ARCHIVED', memberCount: 1_204, externalShared: false, topic: null, isNew: false, selected: false },
];

export const endpointConfig = (over: Partial<EndpointScanConfiguration> = {}): EndpointScanConfiguration => {
  const all = Array.from({ length: 41 }, (_, i) => `type${String(i + 1).padStart(2, '0')}`);
  return {
    configured: true, scanMode: 'cloud', reconciliationStart: '00:00', reconciliationEnd: '00:00',
    includePaths: ['/Users', 'C:\\Users'], excludePaths: [], fileTypes: all.slice(0, 23), allFileTypes: all,
    ...over,
  };
};

/** Asserts a greyed console-only control: aria-disabled, the shared tooltip on hover. */
export function expectConsoleOnly(control: HTMLElement): void {
  expect(control).toHaveAttribute('aria-disabled', 'true');
  const tooltip = document.getElementById(control.getAttribute('aria-describedby') ?? '') as HTMLElement;
  expect(tooltip).not.toBeNull();
  fireEvent.mouseEnter(control);
  expect(tooltip).toBeVisible();
  expect(tooltip).toHaveTextContent(CONSOLE_ONLY_TOOLTIP);
  fireEvent.mouseLeave(control);
}
