import { apiFetch } from '../../utils/apiClient';

/** A banner as the BillOps admin dashboard lists it. */
export interface AdminBanner {
  id: number;
  heading: string;
  body: string;
  targetAll: boolean;
  targetClients: BannerClientOption[];
  createdBy: string | null;
  createdAt: string;
}

/** A banner as a XamOps client sees it. */
export interface ClientBanner {
  id: number;
  heading: string;
  body: string;
  createdAt: string;
}

export interface BannerClientOption {
  id: number;
  name: string;
}

export interface CreateBannerRequest {
  heading: string;
  body: string;
  targetAll: boolean;
  clientIds: number[];
}

export const BANNER_HEADING_MAX = 200;
export const BANNER_BODY_MAX = 5000;

const ADMIN_BASE = '/api/xamops/billops-admin/banners';

/** Reads the backend's `{ message }` error body, falling back to the status code. */
async function errorMessage(res: Response): Promise<string> {
  try {
    const data = await res.json();
    if (data && typeof data.message === 'string') return data.message;
  } catch { /* not JSON */ }
  return `Request failed (HTTP ${res.status})`;
}

// Demo mode answers unknown /api/xamops/* paths with {}, so anything that is not an array is
// treated as empty rather than crashing a .map().
const asArray = <T,>(v: unknown): T[] => (Array.isArray(v) ? (v as T[]) : []);

export async function fetchAdminBanners(): Promise<AdminBanner[]> {
  const res = await apiFetch(ADMIN_BASE);
  if (!res.ok) throw new Error(await errorMessage(res));
  return asArray<AdminBanner>(await res.json());
}

export async function fetchBannerClientOptions(): Promise<BannerClientOption[]> {
  const res = await apiFetch(`${ADMIN_BASE}/client-options`);
  if (!res.ok) throw new Error(await errorMessage(res));
  return asArray<BannerClientOption>(await res.json());
}

export async function createAdminBanner(req: CreateBannerRequest): Promise<AdminBanner> {
  const res = await apiFetch(ADMIN_BASE, { method: 'POST', body: JSON.stringify(req) });
  if (!res.ok) throw new Error(await errorMessage(res));
  return (await res.json()) as AdminBanner;
}

export async function deleteAdminBanner(id: number): Promise<void> {
  const res = await apiFetch(`${ADMIN_BASE}/${id}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(await errorMessage(res));
}

export async function fetchMyBanners(): Promise<ClientBanner[]> {
  const res = await apiFetch('/api/xamops/banners');
  if (!res.ok) throw new Error(await errorMessage(res));
  return asArray<ClientBanner>(await res.json());
}
