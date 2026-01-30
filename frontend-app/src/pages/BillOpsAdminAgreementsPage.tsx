/**
 * BillOps Admin Agreements Page
 *
 * Admin interface for managing billing agreements
 * Converted from: billops/admin_agreements.html
 * 
 * Features:
 * - Upload new agreements as DRAFT
 * - View all agreements (DRAFT + FINALIZED)
 * - Finalize DRAFT agreements (makes visible to users)
 * - Delete agreements
 * - Modern glassmorphism UI design
 * - React sidebar integration
 *
 * Role: ROLE_BILLOPS_ADMIN only
 *
 * What stayed the same:
 * - Same API endpoints
 * - Same business logic
 * - Same data structure
 *
 * What changed:
 * - Migrated from Alpine.js to React with TypeScript
 * - Enhanced modern UI with smooth animations
 * - Uses React sidebar component
 */

import React, { useRef } from 'react';
import { useAdminAgreements } from '../hooks/useAdminAgreements';
import type { Agreement } from '../types/agreements';

const BillOpsAdminAgreementsPage: React.FC = () => {
    const {
        agreements,
        loading,
        error,
        accountId,
        accountName,
        selectedFile,
        uploading,
        uploadError,
        uploadSuccess,
        handleFileSelect,
        uploadAgreement,
        finalizeAgreement,
        deleteAgreement,
    } = useAdminAgreements();

    const fileInputRef = useRef<HTMLInputElement>(null);

    /**
     * Format date to readable string
     */
    const formatDate = (dateString: string | null): string => {
        if (!dateString) return '-';
        try {
            return new Date(dateString).toLocaleDateString('en-US', {
                year: 'numeric',
                month: 'short',
                day: 'numeric',
                hour: '2-digit',
                minute: '2-digit',
            });
        } catch {
            return '-';
        }
    };

    /**
     * Get file icon based on extension
     */
    const getFileIcon = (fileName: string): string => {
        const ext = fileName.split('.').pop()?.toLowerCase();
        if (ext === 'pdf') return 'fas fa-file-pdf';
        if (ext === 'doc' || ext === 'docx') return 'fas fa-file-word';
        return 'fas fa-file-alt';
    };

    /**
     * Get file icon color based on extension
     */
    const getFileIconColor = (fileName: string): string => {
        const ext = fileName.split('.').pop()?.toLowerCase();
        if (ext === 'pdf') return 'admin-agreements-icon-pdf';
        if (ext === 'doc' || ext === 'docx') return 'admin-agreements-icon-word';
        return 'admin-agreements-icon-default';
    };

    /**
     * Handle file input clear after upload
     */
    const clearFileInput = () => {
        if (fileInputRef.current) {
            fileInputRef.current.value = '';
        }
    };

    /**
     * Handle upload button click
     */
    const handleUploadClick = async () => {
        await uploadAgreement();
        clearFileInput();
    };

    return (
        <div className="admin-agreements-page">
            {/* Page Header */}
            <header className="admin-agreements-header">
                <div className="admin-agreements-header-content">
                    <div className="admin-agreements-title-section">
                        <h1 className="admin-agreements-title">
                            <i className="fas fa-file-signature admin-agreements-title-icon"></i>
                            Agreement Administration
                        </h1>
                        <p className="admin-agreements-subtitle">
                            Manage billing agreements and contracts
                        </p>
                    </div>
                    <div className="admin-agreements-account-badge">
                        {accountName ? (
                            <>
                                <i className="fas fa-building"></i>
                                <span>{accountName}</span>
                            </>
                        ) : (
                            <>
                                <i className="fas fa-info-circle"></i>
                                <span>Select an Account</span>
                            </>
                        )}
                    </div>
                </div>
            </header>

            {/* Account Selection Notice */}
            {!accountId && !loading && (
                <div className="admin-agreements-notice">
                    <div className="admin-agreements-notice-icon">
                        <i className="fas fa-exclamation-circle"></i>
                    </div>
                    <div className="admin-agreements-notice-content">
                        <p className="admin-agreements-notice-title">No Account Selected</p>
                        <p className="admin-agreements-notice-text">
                            Please select a Cloud Account from the sidebar to manage agreements.
                        </p>
                    </div>
                </div>
            )}

            {/* Upload Section - Only show when account is selected */}
            {accountId && (
                <div className="admin-agreements-upload-card">
                    <div className="admin-agreements-upload-header">
                        <i className="fas fa-cloud-upload-alt"></i>
                        <h3>Upload New Agreement</h3>
                    </div>

                    <div className="admin-agreements-upload-content">
                        <div className="admin-agreements-file-input-wrapper">
                            <label className="admin-agreements-file-label">
                                Select Document (PDF/Doc)
                            </label>
                            <input
                                type="file"
                                ref={fileInputRef}
                                onChange={handleFileSelect}
                                accept=".pdf,.doc,.docx"
                                className="admin-agreements-file-input"
                            />
                            {selectedFile && (
                                <div className="admin-agreements-selected-file">
                                    <i className={getFileIcon(selectedFile.name)}></i>
                                    <span>{selectedFile.name}</span>
                                </div>
                            )}
                        </div>

                        <button
                            onClick={handleUploadClick}
                            disabled={!selectedFile || uploading}
                            className="admin-agreements-upload-btn"
                        >
                            {uploading ? (
                                <>
                                    <i className="fas fa-spinner fa-spin"></i>
                                    <span>Uploading...</span>
                                </>
                            ) : (
                                <>
                                    <i className="fas fa-cloud-upload-alt"></i>
                                    <span>Upload Draft</span>
                                </>
                            )}
                        </button>
                    </div>

                    {/* Upload Feedback */}
                    {uploadError && (
                        <div className="admin-agreements-upload-error">
                            <i className="fas fa-times-circle"></i>
                            <span>{uploadError}</span>
                        </div>
                    )}
                    {uploadSuccess && (
                        <div className="admin-agreements-upload-success">
                            <i className="fas fa-check-circle"></i>
                            <span>File uploaded successfully!</span>
                        </div>
                    )}
                </div>
            )}

            {/* Agreements Table */}
            {accountId && (
                <div className="admin-agreements-table-card">
                    {/* Loading State */}
                    {loading && (
                        <div className="admin-agreements-loading">
                            <div className="admin-agreements-spinner"></div>
                            <p>Loading agreements...</p>
                        </div>
                    )}

                    {/* Error State */}
                    {error && !loading && (
                        <div className="admin-agreements-error">
                            <i className="fas fa-exclamation-triangle"></i>
                            <p>{error}</p>
                        </div>
                    )}

                    {/* Table */}
                    {!loading && !error && (
                        <>
                            {agreements.length > 0 ? (
                                <div className="admin-agreements-table-container">
                                    <table className="admin-agreements-table">
                                        <thead>
                                            <tr>
                                                <th>File Name</th>
                                                <th>Uploaded At</th>
                                                <th>Uploaded By</th>
                                                <th className="admin-agreements-th-center">Status</th>
                                                <th className="admin-agreements-th-right">Actions</th>
                                            </tr>
                                        </thead>
                                        <tbody>
                                            {agreements.map((agreement: Agreement) => (
                                                <tr key={agreement.id} className="admin-agreements-row">
                                                    <td>
                                                        <div className="admin-agreements-file-info">
                                                            <div className={`admin-agreements-file-icon ${getFileIconColor(agreement.fileName)}`}>
                                                                <i className={getFileIcon(agreement.fileName)}></i>
                                                            </div>
                                                            <span className="admin-agreements-file-name">
                                                                {agreement.fileName}
                                                            </span>
                                                        </div>
                                                    </td>
                                                    <td className="admin-agreements-date">
                                                        {formatDate(agreement.uploadedAt)}
                                                    </td>
                                                    <td className="admin-agreements-uploader">
                                                        {agreement.uploadedBy || 'Unknown'}
                                                    </td>
                                                    <td className="admin-agreements-td-center">
                                                        <span className={`admin-agreements-status ${agreement.status === 'FINALIZED'
                                                                ? 'admin-agreements-status-finalized'
                                                                : 'admin-agreements-status-draft'
                                                            }`}>
                                                            {agreement.status === 'FINALIZED' && (
                                                                <i className="fas fa-check-circle"></i>
                                                            )}
                                                            {agreement.status === 'DRAFT' && (
                                                                <i className="fas fa-edit"></i>
                                                            )}
                                                            <span>{agreement.status}</span>
                                                        </span>
                                                    </td>
                                                    <td className="admin-agreements-actions">
                                                        {/* View Button */}
                                                        <a
                                                            href={agreement.downloadUrl}
                                                            target="_blank"
                                                            rel="noopener noreferrer"
                                                            className="admin-agreements-action-btn admin-agreements-action-view"
                                                            title="View Document"
                                                        >
                                                            <i className="fas fa-eye"></i>
                                                        </a>

                                                        {/* Finalize Button - Only for DRAFT */}
                                                        {agreement.status === 'DRAFT' && (
                                                            <button
                                                                onClick={() => finalizeAgreement(agreement)}
                                                                className="admin-agreements-action-btn admin-agreements-action-finalize"
                                                                title="Finalize Agreement"
                                                            >
                                                                <i className="fas fa-check-circle"></i>
                                                            </button>
                                                        )}

                                                        {/* Delete Button */}
                                                        <button
                                                            onClick={() => deleteAgreement(agreement)}
                                                            className="admin-agreements-action-btn admin-agreements-action-delete"
                                                            title="Delete Agreement"
                                                        >
                                                            <i className="fas fa-trash"></i>
                                                        </button>
                                                    </td>
                                                </tr>
                                            ))}
                                        </tbody>
                                    </table>
                                </div>
                            ) : (
                                <div className="admin-agreements-empty">
                                    <div className="admin-agreements-empty-icon">
                                        <i className="fas fa-folder-open"></i>
                                    </div>
                                    <h3>No Agreements Found</h3>
                                    <p>There are no agreements for this account yet. Upload your first agreement above.</p>
                                </div>
                            )}
                        </>
                    )}
                </div>
            )}
        </div>
    );
};

export default BillOpsAdminAgreementsPage;
