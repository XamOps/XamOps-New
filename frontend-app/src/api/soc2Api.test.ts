import { describe, it, expect, vi, beforeEach } from 'vitest';
import apiClient from '../utils/apiClient';
import {
  fetchSoc2Overview, fetchSoc2Controls, fetchSoc2Vendors,
  publishSoc2Policy, updateSoc2TaskStatus, createSoc2Vendor, createSoc2Risk,
} from './soc2Api';

vi.mock('../utils/apiClient', () => ({
  default: { get: vi.fn(), post: vi.fn(), fetchJson: vi.fn() },
}));

describe('soc2Api', () => {
  beforeEach(() => vi.clearAllMocks());

  it('fetchSoc2Overview calls GET /api/soc2/overview', async () => {
    (apiClient.get as any).mockResolvedValue({ frameworkName: 'SOC 2', controlsTotal: 35 });
    const r = await fetchSoc2Overview();
    expect(apiClient.get).toHaveBeenCalledWith('/api/soc2/overview');
    expect(r.controlsTotal).toBe(35);
  });

  it('fetchSoc2Controls calls GET /api/soc2/controls', async () => {
    (apiClient.get as any).mockResolvedValue([{ id: '1', name: 'MFA', status: 'PASSING' }]);
    const r = await fetchSoc2Controls();
    expect(apiClient.get).toHaveBeenCalledWith('/api/soc2/controls');
    expect(r).toHaveLength(1);
  });

  it('fetchSoc2Vendors calls GET /api/soc2/vendors', async () => {
    (apiClient.get as any).mockResolvedValue([]);
    await fetchSoc2Vendors();
    expect(apiClient.get).toHaveBeenCalledWith('/api/soc2/vendors');
  });

  it('publishSoc2Policy calls POST /api/soc2/policies/:id/publish', async () => {
    (apiClient.post as any).mockResolvedValue({ id: 'pol_1', status: 'published' });
    const r = await publishSoc2Policy('pol_1');
    expect(apiClient.post).toHaveBeenCalledWith('/api/soc2/policies/pol_1/publish');
    expect(r.status).toBe('published');
  });

  it('updateSoc2TaskStatus calls PATCH /api/soc2/tasks/:id with status body', async () => {
    (apiClient.fetchJson as any).mockResolvedValue({ id: 'tsk_1', status: 'done' });
    const r = await updateSoc2TaskStatus('tsk_1', 'done');
    expect(apiClient.fetchJson).toHaveBeenCalledWith('/api/soc2/tasks/tsk_1', {
      method: 'PATCH',
      body: JSON.stringify({ status: 'done' }),
    });
    expect(r.status).toBe('done');
  });

  it('createSoc2Vendor calls POST /api/soc2/vendors with payload', async () => {
    const payload = { name: 'AWS', description: 'Cloud host', category: 'cloud', status: 'assessed' };
    (apiClient.post as any).mockResolvedValue({ id: 'ven_1', ...payload });
    const r = await createSoc2Vendor(payload);
    expect(apiClient.post).toHaveBeenCalledWith('/api/soc2/vendors', payload);
    expect(r.id).toBe('ven_1');
  });

  it('createSoc2Risk calls POST /api/soc2/risks with payload', async () => {
    const payload = { title: 'Offboarding gap', description: 'desc', category: 'people', department: 'hr' };
    (apiClient.post as any).mockResolvedValue({ id: 'risk_1', ...payload });
    const r = await createSoc2Risk(payload);
    expect(apiClient.post).toHaveBeenCalledWith('/api/soc2/risks', payload);
    expect(r.id).toBe('risk_1');
  });
});
