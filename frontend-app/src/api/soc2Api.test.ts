import { describe, it, expect, vi, beforeEach } from 'vitest';
import apiClient from '../utils/apiClient';
import {
  fetchSoc2Overview, fetchSoc2Controls, fetchSoc2Vendors,
  publishSoc2Policy, updateSoc2Task, createSoc2Vendor, createSoc2Risk,
  setSoc2PolicyArchived, updateSoc2Vendor, deleteSoc2Vendor, updateSoc2Risk,
  deleteSoc2Risk, deleteSoc2Control, createSoc2Task, inviteSoc2Member,
  updateSoc2Person, deleteSoc2Person,
} from './soc2Api';

vi.mock('../utils/apiClient', () => ({
  default: { get: vi.fn(), post: vi.fn(), fetchJson: vi.fn(), delete: vi.fn() },
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

  it('updateSoc2Task calls PATCH /api/soc2/tasks/:id with the given fields', async () => {
    (apiClient.fetchJson as any).mockResolvedValue({ id: 'tsk_1', status: 'done' });
    const r = await updateSoc2Task('tsk_1', { status: 'done' });
    expect(apiClient.fetchJson).toHaveBeenCalledWith('/api/soc2/tasks/tsk_1', {
      method: 'PATCH',
      body: JSON.stringify({ status: 'done' }),
    });
    expect(r.status).toBe('done');
  });

  it('createSoc2Task calls POST /api/soc2/tasks with payload', async () => {
    const payload = { title: 'Review logs', description: 'desc', assigneeId: 'mem_1' };
    (apiClient.post as any).mockResolvedValue({ id: 'tsk_9', title: 'Review logs', status: 'todo' });
    const r = await createSoc2Task(payload);
    expect(apiClient.post).toHaveBeenCalledWith('/api/soc2/tasks', payload);
    expect(r.id).toBe('tsk_9');
  });

  it('createSoc2Vendor calls POST /api/soc2/vendors with payload', async () => {
    const payload = { name: 'AWS', description: 'Cloud host', category: 'cloud', status: 'assessed' };
    (apiClient.post as any).mockResolvedValue({ id: 'ven_1', ...payload });
    const r = await createSoc2Vendor(payload);
    expect(apiClient.post).toHaveBeenCalledWith('/api/soc2/vendors', payload);
    expect(r.id).toBe('ven_1');
  });

  it('updateSoc2Vendor calls PATCH /api/soc2/vendors/:id with the given fields', async () => {
    (apiClient.fetchJson as any).mockResolvedValue({ id: 'ven_1', status: 'in_progress' });
    const r = await updateSoc2Vendor('ven_1', { status: 'in_progress' });
    expect(apiClient.fetchJson).toHaveBeenCalledWith('/api/soc2/vendors/ven_1', {
      method: 'PATCH',
      body: JSON.stringify({ status: 'in_progress' }),
    });
    expect(r.status).toBe('in_progress');
  });

  it('deleteSoc2Vendor calls DELETE /api/soc2/vendors/:id', async () => {
    (apiClient.delete as any).mockResolvedValue(undefined);
    await deleteSoc2Vendor('ven_1');
    expect(apiClient.delete).toHaveBeenCalledWith('/api/soc2/vendors/ven_1');
  });

  it('createSoc2Risk calls POST /api/soc2/risks with payload', async () => {
    const payload = { title: 'Offboarding gap', description: 'desc', category: 'people', department: 'hr' };
    (apiClient.post as any).mockResolvedValue({ id: 'risk_1', ...payload });
    const r = await createSoc2Risk(payload);
    expect(apiClient.post).toHaveBeenCalledWith('/api/soc2/risks', payload);
    expect(r.id).toBe('risk_1');
  });

  it('updateSoc2Risk calls PATCH /api/soc2/risks/:id with the given fields', async () => {
    (apiClient.fetchJson as any).mockResolvedValue({ id: 'risk_1', title: 'Updated' });
    const r = await updateSoc2Risk('risk_1', { title: 'Updated' });
    expect(apiClient.fetchJson).toHaveBeenCalledWith('/api/soc2/risks/risk_1', {
      method: 'PATCH',
      body: JSON.stringify({ title: 'Updated' }),
    });
    expect(r.title).toBe('Updated');
  });

  it('deleteSoc2Risk calls DELETE /api/soc2/risks/:id', async () => {
    (apiClient.delete as any).mockResolvedValue(undefined);
    await deleteSoc2Risk('risk_1');
    expect(apiClient.delete).toHaveBeenCalledWith('/api/soc2/risks/risk_1');
  });

  it('deleteSoc2Control calls DELETE /api/soc2/controls/:id', async () => {
    (apiClient.delete as any).mockResolvedValue(undefined);
    await deleteSoc2Control('ctl_1');
    expect(apiClient.delete).toHaveBeenCalledWith('/api/soc2/controls/ctl_1');
  });

  it('setSoc2PolicyArchived calls PATCH /api/soc2/policies/:id/archive with archived flag', async () => {
    (apiClient.fetchJson as any).mockResolvedValue({ id: 'pol_1', status: 'draft' });
    await setSoc2PolicyArchived('pol_1', true);
    expect(apiClient.fetchJson).toHaveBeenCalledWith('/api/soc2/policies/pol_1/archive', {
      method: 'PATCH',
      body: JSON.stringify({ archived: true }),
    });
  });

  it('inviteSoc2Member calls POST /api/soc2/personnel/invite with payload', async () => {
    (apiClient.post as any).mockResolvedValue(undefined);
    await inviteSoc2Member({ email: 'new@xammer.com', role: 'employee' });
    expect(apiClient.post).toHaveBeenCalledWith('/api/soc2/personnel/invite', { email: 'new@xammer.com', role: 'employee' });
  });

  it('updateSoc2Person calls PATCH /api/soc2/personnel/:id with the given fields', async () => {
    (apiClient.fetchJson as any).mockResolvedValue({ id: 'mem_1', role: 'admin' });
    const r = await updateSoc2Person('mem_1', { role: 'admin' });
    expect(apiClient.fetchJson).toHaveBeenCalledWith('/api/soc2/personnel/mem_1', {
      method: 'PATCH',
      body: JSON.stringify({ role: 'admin' }),
    });
    expect(r.role).toBe('admin');
  });

  it('deleteSoc2Person calls DELETE /api/soc2/personnel/:id', async () => {
    (apiClient.delete as any).mockResolvedValue(undefined);
    await deleteSoc2Person('mem_1');
    expect(apiClient.delete).toHaveBeenCalledWith('/api/soc2/personnel/mem_1');
  });
});
