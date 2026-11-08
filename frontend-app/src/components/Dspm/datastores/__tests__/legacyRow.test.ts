import { describe, expect, it } from 'vitest';
import type { DatastoreRow } from '../../../../types/dspmDatastores';
import { legacyLevel, toLegacySaasRow } from '../legacyRow';

const SAAS_ROW: DatastoreRow = {
  id: 'saas-asset-0001',
  sourceId: '0123456789abcdef01234567',
  name: 'Pat Example',
  category: 'saas',
  subtype: 'GMAIL',
  subtypeLabel: 'Gmail',
  risk: { score: null, level: null, levelLabel: null },
  sensitives: { recordCount: 42, typeLabels: ['Personal', 'IT & Security'] },
  source: { provider: 'GOOGLE_WORKSPACE', providerLabel: 'Google Workspace', accountLabel: 'example-workspace', region: null },
  scan: { status: null, statusLabel: null, displayAt: null },
  agent: null,
};

const withLevel = (level: string | null): DatastoreRow => ({ ...SAAS_ROW, risk: { score: null, level, levelLabel: null } });

describe('toLegacySaasRow', () => {
  it('maps a null scan status and label to "" and "Unknown"', () => {
    const row = toLegacySaasRow(SAAS_ROW);
    expect(row.scan.status).toBe('');
    expect(row.scan.statusLabel).toBe('Unknown');
  });

  it('keeps the fields the SaaS drawer reads and sets profile to null', () => {
    const row = toLegacySaasRow(SAAS_ROW);
    expect(row.id).toBe('saas-asset-0001');
    expect(row.name).toBe('Pat Example');
    expect(row.subtypeLabel).toBe('Gmail');
    expect(row.source.provider).toBe('GOOGLE_WORKSPACE');
    expect(row.profile).toBeNull();
  });

  it('fills the rest of the old row shape', () => {
    const row = toLegacySaasRow({ ...SAAS_ROW, scan: { status: 'CLASSIFIED', statusLabel: 'Classified', displayAt: 1_790_000_000 } });
    expect(row.resourceType).toBe('SAAS_PRINCIPAL');
    expect(row.lifecycle).toBeNull();
    expect(row.source.account).toBeNull();
    expect(row.sensitives).toEqual({
      recordCount: 42, objectCount: 0, types: [], typeLabels: ['Personal', 'IT & Security'], level: null, known: true,
    });
    expect(row.scan).toEqual({ status: 'CLASSIFIED', statusLabel: 'Classified', stage: null, lastScannedAt: 1_790_000_000 });
  });

  it('narrows a null level to NONE without a finding', () => {
    expect(toLegacySaasRow(withLevel(null)).risk).toEqual({ score: null, level: 'NONE', policyViolationCount: null, hasFinding: false });
  });

  it('narrows an unknown level such as INFO to NONE', () => {
    expect(toLegacySaasRow(withLevel('INFO')).risk.level).toBe('NONE');
    expect(toLegacySaasRow(withLevel('INFO')).risk.hasFinding).toBe(false);
    expect(legacyLevel('INFO')).toBe('NONE');
  });

  it('keeps HIGH as HIGH with a finding', () => {
    expect(toLegacySaasRow(withLevel('HIGH')).risk).toEqual({ score: null, level: 'HIGH', policyViolationCount: null, hasFinding: true });
  });
});
