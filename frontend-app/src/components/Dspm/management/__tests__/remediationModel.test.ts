import { describe, expect, it } from 'vitest';
import type { RemediationItem } from '../../../../types/dspmPages';
import {
  REMEDIATION_ACTION_OPTIONS, REMEDIATION_STATUS_OPTIONS, approverText, cardActive, cardStatus, isPending, isWebLink,
  nextStatusFilter, statusTone,
} from '../remediationModel';

const item = (status: string | null): RemediationItem => ({
  id: 'run-1', actionType: null, actionLabel: null, objectName: null, objectPath: null, datastoreType: null,
  datastoreLabel: null, sourceType: null, initiatedBy: null, triggerType: null, triggerLabel: null, createdAt: null,
  status, statusLabel: null, approvers: [], scheduledAt: null,
});

describe('filter options', () => {
  it('lists the nine statuses in the console order with its codes', () => {
    expect(REMEDIATION_STATUS_OPTIONS.map(o => o.value)).toEqual([
      'WAITING_APPROVAL', 'IN_PROGRESS', 'SCHEDULED', 'COMPLETED', 'NOOP', 'FAILED', 'REJECTED', 'ROLLED_BACK', 'CANCELLED',
    ]);
    expect(REMEDIATION_STATUS_OPTIONS[0].label).toBe('Waiting Approval');
    expect(REMEDIATION_STATUS_OPTIONS[4].label).toBe('No Change');
  });

  it('lists the nine actions in the console order with its codes', () => {
    expect(REMEDIATION_ACTION_OPTIONS.map(o => o.value)).toEqual([
      'MAKE_PRIVATE', 'REMOVE_EXTERNAL_ACCESS', 'REMOVE_EXTERNAL_ACCESS_BY_TIME', 'REVOKE_RECIPIENT',
      'DOWNGRADE_LINK_SCOPE', 'SET_LINK_EXPIRY', 'MOVE_TO_QUARANTINE', 'DELETE_FILE', 'BLOCK_USER_QUERY',
    ]);
    expect(REMEDIATION_ACTION_OPTIONS[2].label).toBe('Remove External Access (timed)');
  });
});

describe('isPending', () => {
  it('is true for the four statuses that can still change', () => {
    for (const status of ['PENDING', 'WAITING_APPROVAL', 'SCHEDULED', 'IN_PROGRESS']) expect(isPending(item(status)), status).toBe(true);
    for (const status of ['COMPLETED', 'NOOP', 'FAILED', 'REJECTED', 'ROLLED_BACK', 'CANCELLED', null]) {
      expect(isPending(item(status)), String(status)).toBe(false);
    }
  });
});

describe('approverText', () => {
  it('names the first approver and counts the rest', () => {
    expect(approverText([])).toBeNull();
    expect(approverText(['a@example.com'])).toBe('a@example.com');
    expect(approverText(['a@example.com', 'b@example.com', 'c@example.com'])).toBe('a@example.com +2');
  });
});

describe('cardStatus', () => {
  it('maps the single-status cards and leaves Total without a filter', () => {
    expect(cardStatus('total', {})).toBeNull();
    expect(cardStatus('waitingApproval', {})).toBe('WAITING_APPROVAL');
    expect(cardStatus('completed', {})).toBe('COMPLETED');
    expect(cardStatus('failed', {})).toBe('FAILED');
  });

  it('picks the status of In progress / scheduled that has runs, none when both have', () => {
    expect(cardStatus('inProgressScheduled', {})).toBe('IN_PROGRESS');
    expect(cardStatus('inProgressScheduled', { IN_PROGRESS: 2, SCHEDULED: 0 })).toBe('IN_PROGRESS');
    expect(cardStatus('inProgressScheduled', { IN_PROGRESS: 0, SCHEDULED: 1 })).toBe('SCHEDULED');
    expect(cardStatus('inProgressScheduled', { IN_PROGRESS: 1, SCHEDULED: 1 })).toBeNull();
  });
});

describe('cardActive and nextStatusFilter', () => {
  it('marks the card of the chosen status; In progress / scheduled for either of its statuses', () => {
    expect(cardActive('completed', 'COMPLETED')).toBe(true);
    expect(cardActive('completed', '')).toBe(false);
    expect(cardActive('inProgressScheduled', 'SCHEDULED')).toBe(true);
    expect(cardActive('inProgressScheduled', 'IN_PROGRESS')).toBe(true);
    expect(cardActive('total', '')).toBe(false);
  });

  it('sets the clicked status, and clears it when it was already chosen', () => {
    expect(nextStatusFilter('', 'FAILED')).toBe('FAILED');
    expect(nextStatusFilter('COMPLETED', 'FAILED')).toBe('FAILED');
    expect(nextStatusFilter('FAILED', 'FAILED')).toBe('');
  });
});

describe('statusTone and isWebLink', () => {
  it('colours statuses as the console tags them', () => {
    expect(statusTone('WAITING_APPROVAL')).toBe('warning');
    expect(statusTone('SCHEDULED')).toBe('purple');
    expect(statusTone('IN_PROGRESS')).toBe('processing');
    expect(statusTone('COMPLETED')).toBe('success');
    expect(statusTone('FAILED')).toBe('error');
    expect(statusTone('REJECTED')).toBe('error');
    expect(statusTone('NOOP')).toBe('neutral');
    expect(statusTone('SOMETHING_NEW')).toBe('neutral');
    expect(statusTone(null)).toBe('neutral');
  });

  it('links only http and https paths', () => {
    expect(isWebLink('https://drive.example.com/file/d/x')).toBe(true);
    expect(isWebLink('http://intranet.example.com/a')).toBe(true);
    expect(isWebLink('s3://bucket/key')).toBe(false);
    expect(isWebLink('javascript:alert(1)')).toBe(false);
    expect(isWebLink('/shared/finance/file.xlsx')).toBe(false);
    expect(isWebLink(null)).toBe(false);
  });
});
