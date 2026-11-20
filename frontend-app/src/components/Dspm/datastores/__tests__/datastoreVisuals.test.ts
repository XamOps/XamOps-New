import { describe, expect, it } from 'vitest';
import type { DatastoreRow } from '../../../../types/dspmDatastores';
import {
  datastoreIconKey, driveIconKey, endpointScanStatusVisual, saasScanStateVisual, sourceIconKey,
} from '../datastoreVisuals';

type IconRow = Pick<DatastoreRow, 'category' | 'subtype' | 'source'>;

function iconRow(category: DatastoreRow['category'], subtype: string | null, provider: string | null): IconRow {
  return { category, subtype, source: { provider, providerLabel: null, accountLabel: null, region: null } };
}

describe('datastoreIconKey (review fix R16)', () => {
  it('draws a SaaS row by its drive type when it is Gmail or Google Drive', () => {
    expect(datastoreIconKey(iconRow('saas', 'GMAIL', 'GOOGLE_WORKSPACE'))).toBe('GMAIL');
    expect(datastoreIconKey(iconRow('saas', 'GOOGLE_WORKSPACE', 'GOOGLE_WORKSPACE'))).toBe('GOOGLE_DRIVE');
    expect(datastoreIconKey(iconRow('saas', 'GOOGLE_DRIVE', 'GOOGLE_WORKSPACE'))).toBe('GOOGLE_DRIVE');
  });

  it('keeps the provider for any other SaaS subtype and for none', () => {
    expect(datastoreIconKey(iconRow('saas', 'ONEDRIVE', 'MICROSOFT_365'))).toBe('MICROSOFT_365');
    expect(datastoreIconKey(iconRow('saas', null, 'GOOGLE_WORKSPACE'))).toBe('GOOGLE_WORKSPACE');
    expect(datastoreIconKey(iconRow('saas', null, null))).toBeNull();
  });

  it('draws endpoint rows by OS, else provider, and every other row by provider', () => {
    expect(datastoreIconKey(iconRow('endpoint', 'MAC', 'ENDPOINT_AGENT'))).toBe('MAC');
    expect(datastoreIconKey(iconRow('endpoint', null, 'ENDPOINT_AGENT'))).toBe('ENDPOINT_AGENT');
    expect(datastoreIconKey(iconRow('cloud', 'S3', 'S3'))).toBe('S3');
    expect(datastoreIconKey(iconRow('on_prem', 'SELF_HOSTED_POSTGRES', 'SELF_HOSTED_POSTGRES'))).toBe('SELF_HOSTED_POSTGRES');
  });
});

describe('driveIconKey', () => {
  it('maps Gmail to the Gmail mark and both Google Drive codes to the Drive logo', () => {
    expect(driveIconKey('GMAIL')).toBe('GMAIL');
    expect(driveIconKey('GOOGLE_WORKSPACE')).toBe('GOOGLE_DRIVE');
    expect(driveIconKey('GOOGLE_DRIVE')).toBe('GOOGLE_DRIVE');
  });

  it('keeps any other value and gives null for none', () => {
    expect(driveIconKey('ONEDRIVE')).toBe('ONEDRIVE');
    expect(driveIconKey(null)).toBeNull();
    expect(driveIconKey(undefined)).toBeNull();
  });
});

describe('sourceIconKey', () => {
  it('prefers the drive type, then the OS, then the provider', () => {
    expect(sourceIconKey({ driveType: 'GOOGLE_WORKSPACE', os: 'MAC', provider: 'GOOGLE_WORKSPACE' })).toBe('GOOGLE_DRIVE');
    expect(sourceIconKey({ driveType: 'GMAIL', os: null, provider: null })).toBe('GMAIL');
    expect(sourceIconKey({ driveType: null, os: 'WINDOWS', provider: 'ENDPOINT_AGENT' })).toBe('WINDOWS');
    expect(sourceIconKey({ driveType: null, os: null, provider: 'GOOGLE_WORKSPACE' })).toBe('GOOGLE_WORKSPACE');
    expect(sourceIconKey({ driveType: null, os: null, provider: null })).toBeNull();
  });
});

describe('scan visuals', () => {
  it('saasScanStateVisual: completed and enabled green, disabled grey, in progress blue, failed red', () => {
    expect(saasScanStateVisual('COMPLETED')).toEqual({ icon: 'mdi:check-circle', cls: 'text-emerald-500' });
    expect(saasScanStateVisual('ENABLED')).toEqual({ icon: 'mdi:check-circle', cls: 'text-emerald-500' });
    expect(saasScanStateVisual('DISABLED')).toEqual({ icon: 'mdi:circle-half-full', cls: 'text-[rgba(0,0,0,0.4)]' });
    expect(saasScanStateVisual('IN_PROGRESS')).toEqual({ icon: 'mdi:progress-clock', cls: 'text-sky-500' });
    expect(saasScanStateVisual('FAILED')).toEqual({ icon: 'mdi:alert-circle', cls: 'text-rose-500' });
    expect(saasScanStateVisual('PAUSED')).toEqual({ icon: 'mdi:circle-medium', cls: 'text-[rgba(0,0,0,0.4)]' });
    expect(saasScanStateVisual(null)).toEqual({ icon: 'mdi:circle-medium', cls: 'text-[rgba(0,0,0,0.4)]' });
  });

  it('endpointScanStatusVisual: completed green, in progress blue, timed out and failed red', () => {
    expect(endpointScanStatusVisual('COMPLETED')).toEqual({ icon: 'mdi:check-circle', cls: 'text-emerald-500' });
    expect(endpointScanStatusVisual('IN_PROGRESS')).toEqual({ icon: 'mdi:progress-clock', cls: 'text-sky-500' });
    expect(endpointScanStatusVisual('TIMED_OUT')).toEqual({ icon: 'mdi:alert-circle', cls: 'text-rose-500' });
    expect(endpointScanStatusVisual('FAILED')).toEqual({ icon: 'mdi:alert-circle', cls: 'text-rose-500' });
    expect(endpointScanStatusVisual('QUEUED')).toEqual({ icon: 'mdi:circle-medium', cls: 'text-[rgba(0,0,0,0.4)]' });
    expect(endpointScanStatusVisual(undefined)).toEqual({ icon: 'mdi:circle-medium', cls: 'text-[rgba(0,0,0,0.4)]' });
  });
});
