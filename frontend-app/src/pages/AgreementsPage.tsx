/**
 * Agreements Page
 *
 * Display and manage billing agreements and contracts
 * Converted from: billops/agreements.html
 *
 * Features:
 * - List all finalized agreements for selected account
 * - Download agreements as PDF
 * - Clean, professional table layout
 * - Account selection integration
 *
 * What stayed the same:
 * - Same API endpoint: /api/billops/agreements/account/{accountId}
 * - Same data structure
 * - Same download functionality
 * - All business logic preserved
 *
 * What changed:
 * - Migrated from Alpine.js to React with TypeScript
 * - Enhanced UI with modern card design
 * - Smoother animations and transitions
 * - Better loading states
 * - Uses React sidebar
 */

import React from 'react';
import { useAgreements } from '../hooks/useAgreements';

const AgreementsPage: React.FC = () => {
  const {
    agreements,
    loading,
    error,
    accountId,
    accountName,
  } = useAgreements();

  /**
   * Format date to readable string
   */
  const formatDate = (dateString: string | null): string => {
    if (!dateString) return 'N/A';
    try {
      return new Date(dateString).toLocaleDateString('en-US', {
        year: 'numeric',
        month: 'long',
        day: 'numeric',
      });
    } catch {
      return 'N/A';
    }
  };

  return (
    <div className="agreements-page">
      {/* Page Header */}
      <div className="agreements-header">
        <div>
          <h1 className="agreements-title">
            <i className="fas fa-file-contract agreements-title-icon"></i>
            Agreements & Contracts
          </h1>
          <p className="agreements-subtitle">
            {accountName ? `For Account: ${accountName}` : 'Select an account to view agreements'}
          </p>
        </div>
      </div>

      {/* Account Selection Notice */}
      {!accountId && !loading && (
        <div className="agreements-notice">
          <div className="agreements-notice-icon">
            <i className="fas fa-info-circle"></i>
          </div>
          <div className="agreements-notice-content">
            <p className="agreements-notice-text">
              Please select an account from the sidebar to view your agreements and contracts.
            </p>
          </div>
        </div>
      )}

      {/* Loading State */}
      {loading && (
        <div className="agreements-card">
          <div className="agreements-loading">
            <div className="agreements-spinner"></div>
            <p className="agreements-loading-text">Loading agreements...</p>
          </div>
        </div>
      )}

      {/* Error State */}
      {error && !loading && (
        <div className="agreements-error">
          <i className="fas fa-exclamation-triangle agreements-error-icon"></i>
          <p className="agreements-error-text">{error}</p>
        </div>
      )}

      {/* Agreements Table */}
      {!loading && accountId && !error && (
        <div className="agreements-card">
          {agreements.length > 0 ? (
            <div className="agreements-table-container">
              <table className="agreements-table">
                <thead>
                  <tr>
                    <th className="agreements-table-header">Document Name</th>
                    <th className="agreements-table-header">Date Finalized</th>
                    <th className="agreements-table-header agreements-table-header-right">Download</th>
                  </tr>
                </thead>
                <tbody>
                  {agreements.map((agreement) => (
                    <tr key={agreement.id} className="agreements-table-row">
                      <td className="agreements-table-cell">
                        <div className="agreements-doc-info">
                          <div className="agreements-doc-icon">
                            <i className="fas fa-file-contract"></i>
                          </div>
                          <div className="agreements-doc-details">
                            <div className="agreements-doc-name">{agreement.fileName}</div>
                            <div className="agreements-doc-id">ID: {agreement.id}</div>
                          </div>
                        </div>
                      </td>
                      <td className="agreements-table-cell">
                        <div className="agreements-date">
                          <i className="far fa-calendar-check agreements-date-icon"></i>
                          <span>{formatDate(agreement.finalizedAt)}</span>
                        </div>
                      </td>
                      <td className="agreements-table-cell agreements-table-cell-right">
                        <a
                          href={agreement.downloadUrl}
                          target="_blank"
                          rel="noopener noreferrer"
                          className="agreements-download-btn"
                        >
                          <i className="fas fa-download"></i>
                          <span>Download</span>
                        </a>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <div className="agreements-empty">
              <div className="agreements-empty-icon">
                <i className="fas fa-file-signature"></i>
              </div>
              <h3 className="agreements-empty-title">No Agreements Found</h3>
              <p className="agreements-empty-text">
                There are no finalized agreements available for this account yet.
              </p>
            </div>
          )}
        </div>
      )}
    </div>
  );
};

export default AgreementsPage;
