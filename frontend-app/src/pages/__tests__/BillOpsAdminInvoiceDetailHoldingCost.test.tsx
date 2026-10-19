import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

const apiFetch = vi.hoisted(() => vi.fn());
vi.mock('../../utils/apiClient', () => ({ apiFetch }));

import BillOpsAdminInvoiceDetailPage from '../BillOpsAdminInvoiceDetailPage';

/**
 * The editable, optional AWS Marketplace holding cost on the admin invoice detail page.
 *
 * <p>Pins the wiring: the control appears only when there is a Marketplace charge and the
 * invoice is a draft; Apply issues a PUT to the holding-cost endpoint with the numeric
 * percent; Clear sends {@code percent: null}; and after the server returns the recomputed
 * invoice the page shows the derived holding-cost line and the updated Taxable/Total that
 * agree with the server's figures.
 */

const INVOICE_URL = '/api/admin/invoices/166';

const line = (over: Partial<Record<string, unknown>> = {}) => ({
  id: 1,
  serviceName: 'Amazon Elastic Compute Cloud',
  regionName: 'ap-south-1',
  usageType: 'APS3-BoxUsage',
  resourceName: '$0.2 per hour',
  usageQuantity: '100',
  unit: 'Hrs',
  cost: 100,
  ...over,
});

/** A draft AWS invoice carrying a $11994.84 Marketplace charge and no holding cost yet. */
const baseInvoice = {
  id: 166,
  invoiceNumber: 'INV-166',
  invoiceDate: '2026-09-01',
  billingPeriod: '2026-08',
  status: 'DRAFT',
  clientName: 'Example Client',
  accountName: 'example-account',
  provider: 'AWS',
  awsAccountId: '605134457560',
  gcpProjectId: 'N/A',
  azureSubscriptionId: 'N/A',
  preDiscountTotal: 20000,
  discountAmount: 0,
  taxAmount: 3600,
  amount: 23600,
  marketplaceCharge: 11994.84,
  marketplaceHoldingCostPercent: null,
  dataSource: 'CUR',
  lineItems: [line()],
  discounts: [],
  privatePricing: [],
};

/** The same invoice after a 15% holding cost is applied; totals as the server recomputes them. */
const withHoldingCost = {
  ...baseInvoice,
  marketplaceHoldingCostPercent: 15,
  // taxable = 20000 + (13794.06 - 11994.84) margin = 21799.22; GST 18% = 3923.86; total 25723.08
  taxAmount: 3923.86,
  amount: 25723.08,
};

const ok = (body: unknown) => ({ ok: true, status: 200, json: async () => body });

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/billops-admin-invoice-detail?id=166']}>
      <BillOpsAdminInvoiceDetailPage />
    </MemoryRouter>,
  );
}

describe('BillOps admin invoice detail — AWS Marketplace holding cost', () => {
  beforeEach(() => {
    apiFetch.mockReset();
    apiFetch.mockImplementation(async (url: string) => {
      if (url === INVOICE_URL) return ok(baseInvoice);
      return { ok: false, status: 404, json: async () => null };
    });
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 503, json: async () => null })));
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('applies a percent through the holding-cost endpoint and refreshes the totals', async () => {
    renderPage();
    const input = await screen.findByLabelText(/AWS Marketplace holding cost percent/i);

    apiFetch.mockImplementation(async (url: string, init?: RequestInit) => {
      if (url === `${INVOICE_URL}/holding-cost` && init?.method === 'PUT') return ok(withHoldingCost);
      if (url === INVOICE_URL) return ok(baseInvoice);
      return { ok: false, status: 404, json: async () => null };
    });

    await userEvent.type(input, '15');
    await userEvent.click(screen.getByRole('button', { name: /^Apply$/i }));

    await waitFor(() => {
      expect(apiFetch).toHaveBeenCalledWith(
        `${INVOICE_URL}/holding-cost`,
        expect.objectContaining({
          method: 'PUT',
          body: JSON.stringify({ percent: 15 }),
        }),
      );
    });

    // The derived holding-cost summary line appears, and Total due reflects the server figure.
    expect(await screen.findByText(/Holding cost \(AWS Marketplace\)/i)).toBeInTheDocument();
    // Total due reflects the server's recomputed figure (may also appear as Taxable+GST rows).
    expect(screen.getAllByText('$25,723.08').length).toBeGreaterThan(0);
    // The derived margin line shows the grossed-up markup (13794.06 - 11994.84 = 1799.22).
    expect(screen.getByText('+$1,799.22')).toBeInTheDocument();
  });

  it('clears the holding cost by sending percent: null', async () => {
    apiFetch.mockImplementation(async (url: string) => {
      if (url === INVOICE_URL) return ok(withHoldingCost);
      return { ok: false, status: 404, json: async () => null };
    });
    renderPage();
    await screen.findByLabelText(/AWS Marketplace holding cost percent/i);

    apiFetch.mockImplementation(async (url: string, init?: RequestInit) => {
      if (url === `${INVOICE_URL}/holding-cost` && init?.method === 'PUT') return ok(baseInvoice);
      if (url === INVOICE_URL) return ok(withHoldingCost);
      return { ok: false, status: 404, json: async () => null };
    });

    await userEvent.click(screen.getByRole('button', { name: /^Clear$/i }));

    await waitFor(() => {
      expect(apiFetch).toHaveBeenCalledWith(
        `${INVOICE_URL}/holding-cost`,
        expect.objectContaining({
          method: 'PUT',
          body: JSON.stringify({ percent: null }),
        }),
      );
    });
  });

  it('hides the control when there is no Marketplace charge', async () => {
    apiFetch.mockImplementation(async (url: string) => {
      if (url === INVOICE_URL) return ok({ ...baseInvoice, marketplaceCharge: 0 });
      return { ok: false, status: 404, json: async () => null };
    });
    renderPage();
    await screen.findByRole('tab', { name: /Charges by service/i });

    expect(screen.queryByLabelText(/AWS Marketplace holding cost percent/i)).toBeNull();
  });

  it('shows the holding cost read-only on a finalized invoice', async () => {
    apiFetch.mockImplementation(async (url: string) => {
      if (url === INVOICE_URL) return ok({ ...withHoldingCost, status: 'FINALIZED' });
      return { ok: false, status: 404, json: async () => null };
    });
    renderPage();
    await screen.findByRole('tab', { name: /Charges by service/i });

    expect(screen.queryByLabelText(/AWS Marketplace holding cost percent/i)).toBeNull();
    expect(screen.getByText(/15% on AWS Marketplace/i)).toBeInTheDocument();
  });
});
