import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, within, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

const apiFetch = vi.hoisted(() => vi.fn());
vi.mock('../../utils/apiClient', () => ({ apiFetch }));

import BillOpsAdminInvoiceDetailPage from '../BillOpsAdminInvoiceDetailPage';

/**
 * Private pricing applied from the charges tree: setting a rate on a row, and taking it
 * off again.
 *
 * <p>What these pin is the wiring — which row carries the remove control, the scope it
 * sends, the endpoint and method each control calls, and that removing one returns the row
 * to offering a rate.
 *
 * <p>What they cannot pin is the reason removal was unreachable in the first place: the
 * button rendered past the right edge of its column, inside the table wrapper's
 * horizontal overflow. jsdom performs no layout, so every assertion here passed while the
 * control was invisible. That part is held by the stylesheet instead — the rate, its
 * remove button and the discount checkbox are laid out on two lines precisely so they
 * cannot overflow the column, with a comment there saying why.
 */

const INVOICE_URL = '/api/admin/invoices/166';

const line = (over: Partial<Record<string, unknown>> = {}) => ({
  id: 1,
  serviceName: 'Amazon Elastic Compute Cloud',
  regionName: 'ap-south-1',
  usageType: 'APS3-NatGateway-Hours',
  resourceName: '$0.056 per NAT Gateway Hour',
  usageQuantity: '1422',
  unit: 'Hrs',
  cost: 79.63,
  ...over,
});

/** A draft with one priced usage type, as it looks after a rate has been applied. */
const pricedInvoice = {
  id: 166,
  invoiceNumber: 'INV-166',
  invoiceDate: '2026-10-01',
  billingPeriod: '2026-09',
  status: 'DRAFT',
  clientName: 'Example Client',
  accountName: 'example-account',
  provider: 'AWS',
  awsAccountId: '605134457560',
  gcpProjectId: 'N/A',
  azureSubscriptionId: 'N/A',
  preDiscountTotal: 108.07,
  discountAmount: 0,
  taxAmount: 19.45,
  amount: 127.52,
  privatePricedTotal: 108.07,
  discountShieldedTotal: 108.07,
  dataSource: 'CUR',
  lineItems: [line({
    cost: 108.07, privateUnitRate: 0.076, privateRateUnit: 'Hrs',
    originalCost: 79.63, discountShielded: true,
  })],
  discounts: [],
  privatePricing: [{
    id: 42,
    serviceName: 'Amazon Elastic Compute Cloud',
    regionName: 'ap-south-1',
    usageType: 'APS3-NatGateway-Hours',
    scopeLabel: 'Amazon Elastic Compute Cloud · ap-south-1 · APS3-NatGateway-Hours',
    unitRate: 0.076,
    rateUnit: 'Hrs',
    excludeFromOverallDiscount: true,
    lineCount: 1,
    skippedLineCount: 0,
    originalAmount: 79.63,
    pricedAmount: 108.07,
    description: '$0.076 per Hrs · 1 line · shielded from overall discount',
  }],
};

/** The same draft before any rate was applied. */
const plainInvoice = {
  ...pricedInvoice,
  preDiscountTotal: 79.63,
  amount: 93.96,
  taxAmount: 14.33,
  privatePricedTotal: 0,
  discountShieldedTotal: 0,
  lineItems: [line()],
  privatePricing: [],
};

const ok = (body: unknown) => ({ ok: true, status: 200, json: async () => body });

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/billops-admin-invoice-detail?id=166']}>
      <BillOpsAdminInvoiceDetailPage />
    </MemoryRouter>,
  );
}

/** Renders, then opens the tree down to the usage type row the rate in these tests sits on. */
async function expandToUsageType() {
  renderPage();
  await userEvent.click(await screen.findByRole('button', { name: /Expand all/i }));
  return screen.findByText('APS3-NatGateway-Hours');
}

const rowOf = (text: string) =>
  (screen.getByText(text).closest('tr')) as HTMLElement;

describe('BillOps admin invoice detail — private pricing in the charges tree', () => {
  beforeEach(() => {
    apiFetch.mockReset();
    apiFetch.mockImplementation(async (url: string) => {
      if (url === INVOICE_URL) return ok(pricedInvoice);
      return { ok: false, status: 404, json: async () => null };
    });
    // The page fetches a live USD→INR rate for a display-only line.
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 503, json: async () => null })));
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('offers a remove control on the row that carries the rate', async () => {
    await expandToUsageType();

    const remove = within(rowOf('APS3-NatGateway-Hours'))
      .getByRole('button', { name: /Remove the rate on/i });

    expect(remove).toBeEnabled();
  });

  it('removes the rate through the private-pricing endpoint when that control is used', async () => {
    await expandToUsageType();

    apiFetch.mockImplementation(async (url: string, init?: RequestInit) => {
      if (url === `${INVOICE_URL}/private-pricing/42` && init?.method === 'DELETE') {
        return ok(plainInvoice);
      }
      if (url === INVOICE_URL) return ok(pricedInvoice);
      return { ok: false, status: 404, json: async () => null };
    });

    await userEvent.click(within(rowOf('APS3-NatGateway-Hours'))
      .getByRole('button', { name: /Remove the rate on/i }));

    await waitFor(() => {
      expect(apiFetch).toHaveBeenCalledWith(
        `${INVOICE_URL}/private-pricing/42`,
        expect.objectContaining({ method: 'DELETE' }),
      );
    });

    // The charge is back at provider cost, and the rate control is on offer again.
    await waitFor(() => {
      expect(within(rowOf('APS3-NatGateway-Hours'))
        .queryByRole('button', { name: /Remove the rate on/i })).toBeNull();
    });
    expect(within(rowOf('APS3-NatGateway-Hours'))
      .getByRole('button', { name: /Set rate/i })).toBeInTheDocument();
  });

  it('puts the discount checkbox on the priced row, checked, and can clear it', async () => {
    await expandToUsageType();

    const shield = within(rowOf('APS3-NatGateway-Hours'))
      .getByRole('checkbox', { name: /Exclude .* from the overall discount/i });
    expect(shield).toBeChecked();

    apiFetch.mockImplementation(async (url: string, init?: RequestInit) => {
      if (url === `${INVOICE_URL}/private-pricing/42` && init?.method === 'PATCH') {
        return ok({ ...pricedInvoice, discountShieldedTotal: 0,
          privatePricing: [{ ...pricedInvoice.privatePricing[0], excludeFromOverallDiscount: false }] });
      }
      if (url === INVOICE_URL) return ok(pricedInvoice);
      return { ok: false, status: 404, json: async () => null };
    });

    await userEvent.click(shield);

    await waitFor(() => {
      expect(apiFetch).toHaveBeenCalledWith(
        `${INVOICE_URL}/private-pricing/42`,
        expect.objectContaining({
          method: 'PATCH',
          body: JSON.stringify({ excludeFromOverallDiscount: false }),
        }),
      );
    });
  });

  it('scopes the rate to the row it was set from', async () => {
    apiFetch.mockImplementation(async (url: string) => {
      if (url === INVOICE_URL) return ok(plainInvoice);
      return { ok: false, status: 404, json: async () => null };
    });
    await expandToUsageType();

    await userEvent.click(within(rowOf('APS3-NatGateway-Hours'))
      .getByRole('button', { name: /Set rate/i }));

    await userEvent.type(screen.getByLabelText(/Your rate \(USD\)/i), '0.076');

    apiFetch.mockImplementation(async (url: string, init?: RequestInit) => {
      if (url === `${INVOICE_URL}/private-pricing` && init?.method === 'POST') return ok(pricedInvoice);
      if (url === INVOICE_URL) return ok(plainInvoice);
      return { ok: false, status: 404, json: async () => null };
    });

    await userEvent.click(screen.getByRole('button', { name: /Apply rate/i }));

    await waitFor(() => {
      expect(apiFetch).toHaveBeenCalledWith(
        `${INVOICE_URL}/private-pricing`,
        expect.objectContaining({
          method: 'POST',
          // The region is the stored value, not the "Global" label the tree shows when a
          // charge carries no region.
          body: JSON.stringify({
            serviceName: 'Amazon Elastic Compute Cloud',
            regionName: 'ap-south-1',
            usageType: 'APS3-NatGateway-Hours',
            unitRate: 0.076,
            rateUnit: 'Hrs',
            excludeFromOverallDiscount: true,
          }),
        }),
      );
    });
  });

  it('shows the rate and the provider cost it replaced on the charge line', async () => {
    await expandToUsageType();

    const charge = rowOf('$0.056 per NAT Gateway Hour');
    // Phrased as the detail bill phrases it, unit included, so the page and the
    // customer's bill describe one rate the same way.
    expect(within(charge).getByText(/\$0\.076 per Hrs/)).toBeInTheDocument();
    expect(within(charge).getByText(/was \$79\.63/)).toBeInTheDocument();
    expect(within(charge).getByText('$108.07')).toBeInTheDocument();
  });

  it('has no Private pricing tab: rates are set from the charges tree', async () => {
    renderPage();
    await screen.findByRole('tab', { name: /Charges by service/i });

    expect(screen.queryByRole('tab', { name: /Private pricing/i })).toBeNull();
    expect(screen.getByRole('tab', { name: /Discounts/i })).toBeInTheDocument();
  });
});
