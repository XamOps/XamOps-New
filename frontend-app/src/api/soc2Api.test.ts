import { describe, it, expect, vi, beforeEach } from 'vitest';
import apiClient from '../utils/apiClient';
import { fetchSoc2Overview, fetchSoc2Controls, fetchSoc2Vendors } from './soc2Api';

vi.mock('../utils/apiClient', () => ({
  default: { get: vi.fn() },
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
});
