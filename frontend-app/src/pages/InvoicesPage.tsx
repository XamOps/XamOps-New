/**
 * Invoices Page
 *
 * Converted from: billops/invoices.html
 *
 * Invoice management with list and detail views:
 * - Filter by year/month
 * - List view with all invoices
 * - Detail view with drilldown (Service -> Region -> Resource)
 * - PDF download functionality
 * - Expandable/collapsible breakdown
 *
 * What stayed the same:
 * - Same API endpoints
 * - Same data structure and fields
 * - Same caching strategy (localStorage)
 * - Same filtering and sorting logic
 * - All business logic preserved exactly
 *
 * What changed:
 * - Migrated from Alpine.js to React with TypeScript
 * - Using custom hook for data fetching
 * - Enhanced UI with smoother animations
 * - Better TypeScript type safety
 */

import React from 'react';
import { useInvoices } from '../hooks/useInvoices';
import Big from 'big.js';
import type { Invoice, InvoiceDiscount } from '../types/invoices';
import '../styles/invoices.css';

const InvoicesPage: React.FC = () => {
  const {
    filteredInvoices,
    selectedInvoice,
    detailedBreakdown,
    expandedServices,
    expandedRegions,

    selectedYear,
    selectedMonth,
    years,
    months,

    selectedAccountId,
    selectedAccountName,

    viewState,
    listLoading,
    detailLoading,
    isUpdating,

    error,
    updateError,
    lastUpdatedDisplay,

    hasData,

    setSelectedYear,
    setSelectedMonth,
    handleFilterChange,
    fetchData,
    viewInvoice,
    goBackToList,
    downloadInvoice,
    toggleService,
    toggleRegion,
  } = useInvoices();

  /**
   * Format currency
   */
  const formatCurrency = (value: number | undefined | null): string => {
    if (typeof value !== 'number') return '$0.00';
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
    }).format(value);
  };

  /**
   * Format number
   */
  const formatNumber = (value: number | string | null | undefined): string => {
    if (typeof value !== 'number' && typeof value !== 'string') return '0';
    const num = Number(value);
    if (isNaN(num)) return '0';
    return num.toLocaleString('en-US', { maximumFractionDigits: 3 });
  };

  /**
   * Calculate discount amount using Big.js
   */
  const calculateDiscountAmount = (discount: InvoiceDiscount): number => {
    if (!selectedInvoice) return 0;
    try {
      let amount = 0;
      const percentage = new Big(discount.percentage).div(100);

      if (discount.serviceName === 'ALL') {
        amount = new Big(selectedInvoice.preDiscountTotal).times(percentage).toNumber();
      } else {
        const service = detailedBreakdown.find((s) => s.serviceName === discount.serviceName);
        if (service) {
          amount = new Big(service.totalCost).times(percentage).toNumber();
        }
      }
      return amount * -1;
    } catch (e) {
      return 0;
    }
  };

  return (
    <div className="flex-1 flex flex-col overflow-hidden">
      <main
        id="main-content"
        className="flex-1 overflow-x-hidden overflow-y-auto bg-gray-100 p-6"
      >
        <div className="container mx-auto">
          {/* Header */}
          <div className="flex justify-between items-center mb-6">
            <div>
              <h1 className="text-3xl font-bold text-gray-800 flex items-center">
                View Invoices
                {isUpdating && (
                  <span className="updating-spinner ml-3" title="Refreshing data in background..." />
                )}
              </h1>
              <div className="flex items-center space-x-2 mt-1">
                <p className="text-gray-600">
                  {selectedAccountName
                    ? `Account: ${selectedAccountName} (${selectedAccountId})`
                    : 'No account selected'}
                </p>
                {lastUpdatedDisplay && (
                  <span className="text-xs text-gray-400 font-medium bg-gray-100 px-2 py-0.5 rounded flex items-center">
                    <i className="far fa-clock mr-1" />
                    Last Updated: <span className="ml-1">{lastUpdatedDisplay}</span>
                  </span>
                )}
              </div>
            </div>
          </div>

          {/* Filters */}
          <div className="bg-white p-4 rounded-lg shadow-md mb-6 flex items-center space-x-4">
            <div>
              <label htmlFor="year" className="block text-sm font-medium text-gray-700">
                Year
              </label>
              <select
                id="year"
                value={selectedYear}
                onChange={(e) => {
                  setSelectedYear(e.target.value);
                  handleFilterChange();
                }}
                className="mt-1 block w-full pl-3 pr-10 py-2 text-base border-gray-300 focus:outline-none focus:ring-indigo-500 focus:border-indigo-500 sm:text-sm rounded-md"
                disabled={listLoading || isUpdating}
              >
                <option value="all">All Years</option>
                {years.map((year) => (
                  <option key={year} value={year}>
                    {year}
                  </option>
                ))}
              </select>
            </div>

            <div>
              <label htmlFor="month" className="block text-sm font-medium text-gray-700">
                Month
              </label>
              <select
                id="month"
                value={selectedMonth}
                onChange={(e) => {
                  setSelectedMonth(e.target.value);
                  handleFilterChange();
                }}
                className="mt-1 block w-full pl-3 pr-10 py-2 text-base border-gray-300 focus:outline-none focus:ring-indigo-500 focus:border-indigo-500 sm:text-sm rounded-md"
                disabled={listLoading || isUpdating}
              >
                <option value="all">All Months</option>
                {months.map((month) => (
                  <option key={month.value} value={month.value}>
                    {month.name}
                  </option>
                ))}
              </select>
            </div>

            {viewState === 'detail' && (
              <>
                <div className="pt-6">
                  <button
                    onClick={downloadInvoice}
                    disabled={!selectedInvoice || detailLoading}
                    className="px-4 py-2 bg-indigo-600 text-white rounded-md hover:bg-indigo-700 disabled:bg-gray-400 transition-colors"
                  >
                    <i className="fas fa-download mr-2" />
                    Download PDF
                  </button>
                </div>
                <div className="pt-6">
                  <button
                    onClick={goBackToList}
                    className="px-4 py-2 bg-gray-500 text-white rounded-md hover:bg-gray-600 transition-colors"
                  >
                    <i className="fas fa-arrow-left mr-2" />
                    Back to List
                  </button>
                </div>
              </>
            )}

            {viewState === 'list' && (
              <div className="pt-6">
                <button
                  onClick={() => fetchData(true)}
                  className="px-4 py-2 bg-white text-gray-700 border border-gray-300 rounded-md hover:bg-gray-50 disabled:bg-gray-200 transition-colors"
                  disabled={listLoading || isUpdating}
                >
                  <i className={`fas ${isUpdating ? 'fa-sync-alt fa-spin' : 'fa-sync-alt'}`} />
                  <span className="ml-2">{isUpdating ? 'Refreshing...' : 'Refresh'}</span>
                </button>
              </div>
            )}
          </div>

          {/* Update Error */}
          {updateError && hasData && (
            <div
              className="mb-4 p-3 text-sm text-yellow-800 rounded-lg bg-yellow-50 border border-yellow-200"
              role="alert"
            >
              <i className="fas fa-exclamation-circle mr-2" />
              <span>{updateError}</span>
            </div>
          )}

          {/* Error */}
          {error && !hasData && (
            <div className="text-center p-10 bg-red-50 rounded-lg shadow-md border border-red-100">
              <i className="fas fa-exclamation-triangle text-red-400 text-2xl mb-2" />
              <p className="text-red-700 font-medium">{error}</p>
              <button
                onClick={() => fetchData(true)}
                className="mt-3 text-red-600 hover:underline text-sm"
              >
                Try Again
              </button>
            </div>
          )}

          {/* Loading Skeleton */}
          {listLoading && (
            <div className="bg-white rounded-lg shadow-md overflow-hidden p-6 space-y-4">
              <div className="h-8 bg-gray-200 rounded w-full skeleton-pulse" />
              <div className="h-8 bg-gray-200 rounded w-full skeleton-pulse" />
              <div className="h-8 bg-gray-200 rounded w-full skeleton-pulse" />
              <div className="h-8 bg-gray-200 rounded w-full skeleton-pulse" />
            </div>
          )}

          {/* Invoice List */}
          {!listLoading && viewState === 'list' && filteredInvoices.length > 0 && (
            <div className="bg-white rounded-lg shadow-md overflow-hidden">
              <table className="min-w-full divide-y divide-gray-200">
                <thead className="bg-gray-50">
                  <tr>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      Invoice #
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      Account
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      Period
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      Total
                    </th>
                    <th className="px-6 py-3 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">
                      Status
                    </th>
                    <th className="px-6 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">
                      Actions
                    </th>
                  </tr>
                </thead>
                <tbody className="bg-white divide-y divide-gray-200">
                  {filteredInvoices.map((invoice) => (
                    <tr key={invoice.id} className="hover:bg-gray-50 transition-colors">
                      <td className="px-6 py-4 whitespace-nowrap">
                        <button
                          onClick={() => viewInvoice(invoice)}
                          className="text-indigo-600 hover:underline font-medium"
                        >
                          {invoice.invoiceNumber}
                        </button>
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap font-mono text-xs">
                        {invoice.awsAccountId && invoice.awsAccountId !== 'N/A'
                          ? invoice.awsAccountId
                          : invoice.gcpProjectId && invoice.gcpProjectId !== 'N/A'
                            ? invoice.gcpProjectId
                            : invoice.azureSubscriptionId}
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap">{invoice.billingPeriod}</td>
                      <td className="px-6 py-4 whitespace-nowrap font-mono text-gray-700">
                        {formatCurrency(invoice.amount)}
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap text-center">
                        <span
                          className={`px-2 inline-flex text-xs leading-5 font-semibold rounded-full ${
                            invoice.status === 'FINALIZED'
                              ? 'bg-green-100 text-green-800'
                              : 'bg-gray-100 text-gray-800'
                          }`}
                        >
                          {invoice.status}
                        </span>
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap text-right text-sm font-medium">
                        <button
                          onClick={() => viewInvoice(invoice)}
                          className="text-indigo-600 hover:text-indigo-900"
                        >
                          View
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {/* Invoice Detail View */}
          {!detailLoading && viewState === 'detail' && selectedInvoice && (
            <div className="space-y-6">
              <div className="bg-white rounded-lg shadow-md p-8">
                {/* Invoice Header */}
                <div className="flex justify-between items-start mb-8">
                  <div>
                    <h2 className="text-2xl font-bold text-gray-800">
                      Invoice #{selectedInvoice.invoiceNumber}
                    </h2>
                    <p className="text-gray-500">
                      Account ID:{' '}
                      {selectedInvoice.awsAccountId && selectedInvoice.awsAccountId !== 'N/A'
                        ? selectedInvoice.awsAccountId
                        : selectedInvoice.gcpProjectId && selectedInvoice.gcpProjectId !== 'N/A'
                          ? selectedInvoice.gcpProjectId
                          : selectedInvoice.azureSubscriptionId}
                    </p>
                  </div>
                  <div className="text-right">
                    <p className="font-semibold text-gray-700">
                      Billing Period: {selectedInvoice.billingPeriod}
                    </p>
                    <p className="text-gray-500">
                      Generated Date: {selectedInvoice.invoiceDate}
                    </p>
                  </div>
                </div>

                {/* Summary Cards */}
                <div className="grid grid-cols-1 md:grid-cols-3 gap-6 mb-8">
                  <div className="bg-gray-50 p-4 rounded-lg text-center">
                    <h3 className="text-sm font-medium text-gray-500">Original Total</h3>
                    <p className="mt-1 text-2xl font-semibold text-gray-600">
                      {formatCurrency(selectedInvoice.preDiscountTotal)}
                    </p>
                  </div>
                  <div className="bg-red-50 p-4 rounded-lg text-center">
                    <h3 className="text-sm font-medium text-red-500">Discounts Applied</h3>
                    <p className="mt-1 text-2xl font-semibold text-red-700">
                      {formatCurrency(selectedInvoice.discountAmount * -1)}
                    </p>
                  </div>
                  <div className="bg-green-50 p-4 rounded-lg text-center">
                    <h3 className="text-sm font-medium text-green-600">Final Invoice Total</h3>
                    <p className="mt-1 text-3xl font-bold text-green-800">
                      {formatCurrency(selectedInvoice.amount)}
                    </p>
                  </div>
                </div>

                {/* Cost Breakdown Table */}
                <h2 className="text-xl font-bold text-gray-800 mb-4">Final Charges</h2>
                <div className="border rounded-lg overflow-hidden">
                  <table className="cost-breakdown-table">
                    <colgroup>
                      <col style={{ width: '60px' }} />
                      <col style={{ width: '45%' }} />
                      <col style={{ width: '25%' }} />
                      <col style={{ width: '25%' }} />
                    </colgroup>
                    <thead>
                      <tr>
                        <th />
                        <th>Service / Resource</th>
                        <th>Usage Quantity</th>
                        <th>Amount (USD)</th>
                      </tr>
                    </thead>

                    {detailedBreakdown.map((service) => (
                      <tbody key={service.serviceName}>
                        {/* Service Row */}
                        <tr
                          onClick={() => toggleService(service.serviceName)}
                          className="cursor-pointer"
                        >
                          <td>
                            <div
                              className={`expand-icon ${expandedServices[service.serviceName] ? 'rotate-90' : ''}`}
                            >
                              <i className="fas fa-chevron-right text-sm" />
                            </div>
                          </td>
                          <td>
                            <div className="service-name">{service.serviceName}</div>
                          </td>
                          <td>
                            <span className="text-gray-500">—</span>
                          </td>
                          <td>
                            <div className="amount-cell">{formatCurrency(service.totalCost)}</div>
                          </td>
                        </tr>

                        {/* Region Rows */}
                        {expandedServices[service.serviceName] &&
                          service.regionCosts.map((region) => (
                            <React.Fragment key={region.regionName}>
                              <tr
                                onClick={() => toggleRegion(service.serviceName, region.regionName)}
                                className="cursor-pointer drilldown-row"
                              >
                                <td>
                                  <div
                                    className={`expand-icon ${expandedRegions[`${service.serviceName}-${region.regionName}`] ? 'rotate-90' : ''}`}
                                  >
                                    <i className="fas fa-chevron-right text-xs" />
                                  </div>
                                </td>
                                <td>
                                  <div className="region-name">{region.regionName}</div>
                                </td>
                                <td>
                                  <span className="text-gray-500">—</span>
                                </td>
                                <td>
                                  <div className="amount-cell text-blue-600">
                                    {formatCurrency(region.cost)}
                                  </div>
                                </td>
                              </tr>

                              {/* Resource Rows */}
                              {expandedRegions[`${service.serviceName}-${region.regionName}`] &&
                                region.resources.map((resource) => (
                                  <tr key={resource.resourceId} className="nested-drilldown">
                                    <td />
                                    <td>
                                      <div className="resource-name">{resource.resourceName}</div>
                                    </td>
                                    <td className="text-right text-gray-600 text-sm">
                                      {resource.quantity != null
                                        ? `${formatNumber(resource.quantity)} ${resource.unit || ''}`
                                        : 'N/A'}
                                    </td>
                                    <td>
                                      <div className="amount-cell text-purple-600">
                                        {formatCurrency(resource.cost)}
                                      </div>
                                    </td>
                                  </tr>
                                ))}
                            </React.Fragment>
                          ))}
                      </tbody>
                    ))}

                    {/* Empty State */}
                    {detailedBreakdown.length === 0 && (
                      <tbody>
                        <tr>
                          <td colSpan={4} className="text-center py-12">
                            <i className="fas fa-inbox text-gray-300 text-3xl mb-3" />
                            <p className="text-gray-500 font-medium">
                              No detailed cost data available for the selected period.
                            </p>
                          </td>
                        </tr>
                      </tbody>
                    )}

                    {/* Footer - Totals */}
                    <tfoot className="bg-gray-50 text-sm">
                      <tr className="border-t-2 border-gray-200">
                        <td colSpan={3} className="px-6 py-2 text-right text-gray-600 font-medium">
                          Subtotal
                        </td>
                        <td className="px-6 py-2 text-right text-gray-800 font-bold">
                          {formatCurrency(selectedInvoice.preDiscountTotal)}
                        </td>
                      </tr>

                      {/* Discounts */}
                      {selectedInvoice.discounts.map((discount) => (
                        <tr key={discount.id} className="bg-red-50">
                          <td colSpan={3} className="px-6 py-2 text-right text-red-600 text-xs">
                            <span>{discount.description}</span>
                          </td>
                          <td className="px-6 py-2 text-right text-red-700 font-medium">
                            {formatCurrency(calculateDiscountAmount(discount))}
                          </td>
                        </tr>
                      ))}

                      <tr className="border-t border-gray-200 bg-gray-50">
                        <td colSpan={3} className="px-6 py-2 text-right text-gray-500">
                          Taxable Value
                        </td>
                        <td className="px-6 py-2 text-right text-gray-700">
                          {formatCurrency(
                            selectedInvoice.preDiscountTotal - selectedInvoice.discountAmount
                          )}
                        </td>
                      </tr>

                      <tr className="bg-gray-50">
                        <td colSpan={3} className="px-6 py-2 text-right text-gray-500">
                          IGST (18%)
                        </td>
                        <td className="px-6 py-2 text-right text-gray-700">
                          {formatCurrency(selectedInvoice.taxAmount)}
                        </td>
                      </tr>

                      <tr className="border-t-2 border-indigo-100 bg-indigo-50">
                        <td
                          colSpan={3}
                          className="px-6 py-4 text-right text-indigo-900 font-bold text-lg"
                        >
                          TOTAL DUE
                        </td>
                        <td className="px-6 py-4 text-right text-indigo-700 font-bold text-xl">
                          {formatCurrency(selectedInvoice.amount)}
                        </td>
                      </tr>
                    </tfoot>
                  </table>
                </div>
              </div>
            </div>
          )}
        </div>
      </main>
    </div>
  );
};

export default InvoicesPage;
