import React, { useRef, useEffect } from 'react';
import { useAdminAgreements } from '../hooks/useAdminAgreements';
import { useTopbar } from '../contexts/TopbarContext';
import type { Agreement } from '../types/agreements';
import { AlertDialog } from '../components/ui';

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
        pendingFinalize,
        pendingDelete,
        requestFinalize,
        cancelFinalize,
        requestDelete,
        cancelDelete,
    } = useAdminAgreements();

    const { setActions, clearActions } = useTopbar();
    const fileInputRef = useRef<HTMLInputElement>(null);

    const finalizedCount = agreements.filter(a => a.status === 'FINALIZED').length;
    const draftCount = agreements.filter(a => a.status === 'DRAFT').length;

    /**
     * Set Topbar Actions and Stats
     */
    useEffect(() => {
        if (accountId) {
            setActions(
                <div className="flex items-center gap-6">
                    <div className="flex items-center gap-4">
                        <div className="text-center bg-white px-4 py-2 rounded-xl border border-gray-200 shadow-sm hidden sm:block transition-all hover:shadow-md hover:border-indigo-200">
                            <div className="text-sm font-black text-indigo-600 tracking-tight">
                                {agreements.length}
                            </div>
                            <div className="text-[10px] text-gray-500 font-bold uppercase tracking-widest">Total Agreements</div>
                        </div>
                        <div className="text-center bg-white px-4 py-2 rounded-xl border border-gray-200 shadow-sm hidden md:block transition-all hover:shadow-md hover:border-green-200">
                            <div className="text-sm font-black text-green-600 tracking-tight">{finalizedCount}</div>
                            <div className="text-[10px] text-gray-500 font-bold uppercase tracking-widest">Finalized</div>
                        </div>
                        <div className="text-center bg-white px-4 py-2 rounded-xl border border-gray-200 shadow-sm hidden md:block transition-all hover:shadow-md hover:border-amber-200">
                            <div className="text-sm font-black text-amber-600 tracking-tight">{draftCount}</div>
                            <div className="text-[10px] text-gray-500 font-bold uppercase tracking-widest">Drafts</div>
                        </div>
                    </div>
                </div>
            );
        } else {
            setActions(null);
        }
        return () => clearActions();
    }, [setActions, clearActions, agreements.length, finalizedCount, draftCount, accountId]);

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
        if (ext === 'pdf') return 'fas fa-file-pdf text-red-500';
        if (ext === 'doc' || ext === 'docx') return 'fas fa-file-word text-blue-500';
        return 'fas fa-file-alt text-indigo-500';
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
        <div className="flex-1 flex flex-col h-full overflow-hidden bg-transparent">
            <AlertDialog
                isOpen={!!pendingFinalize}
                title={`Finalize "${pendingFinalize?.fileName}"?`}
                description="This will make the agreement visible to the user."
                confirmLabel="Finalize"
                variant="info"
                onConfirm={() => pendingFinalize && finalizeAgreement(pendingFinalize)}
                onCancel={cancelFinalize}
            />
            <AlertDialog
                isOpen={!!pendingDelete}
                title={`Delete "${pendingDelete?.fileName}"?`}
                description="This action cannot be undone."
                confirmLabel="Delete"
                variant="danger"
                onConfirm={() => pendingDelete && deleteAgreement(pendingDelete)}
                onCancel={cancelDelete}
            />
            {/* Main Content */}
            <main className="flex-1 overflow-y-auto p-8 custom-scrollbar relative z-0">

                {/* Account Selection Notice */}
                {!accountId && !loading && (
                    <div className="max-w-3xl mx-auto mt-20">
                        <div className="bg-white border border-indigo-100 rounded-3xl p-12 text-center shadow-xl shadow-indigo-500/5 animate-fade-in">
                            <div className="w-20 h-20 bg-indigo-50 rounded-2xl flex items-center justify-center mx-auto mb-6">
                                <i className="fas fa-exclamation-circle text-3xl text-indigo-600" />
                            </div>
                            <h2 className="text-2xl font-black text-gray-900 mb-4">Account Required</h2>
                            <p className="text-gray-500 font-medium leading-relaxed max-w-sm mx-auto">
                                Please select a Cloud Account from the sidebar to manage agreements and contracts for your organization.
                            </p>
                        </div>
                    </div>
                )}

                {/* Data Section */}
                {accountId && (
                    <div className="max-w-[1600px] mx-auto space-y-8 animate-fade-in">

                        {/* Summary for Selected Account */}
                        <div className="bg-white rounded-2xl border border-gray-200 p-6 flex flex-col md:flex-row items-center justify-between gap-6 shadow-sm">
                            <div className="flex items-center gap-4 text-left w-full md:w-auto">
                                <div className="w-12 h-12 bg-indigo-50 rounded-xl flex items-center justify-center text-xl text-indigo-600 shadow-sm border border-indigo-100">
                                    <i className="fas fa-building" />
                                </div>
                                <div>
                                    <h2 className="text-lg font-bold text-gray-900 leading-tight">{accountName || 'Selected Account'}</h2>
                                    <p className="text-xs text-gray-500 font-medium uppercase tracking-wider mt-0.5">Managing documents for this account</p>
                                </div>
                            </div>

                            {/* Stats mini-grid inside main area too for context */}
                            <div className="flex items-center gap-8 w-full md:w-auto justify-start md:justify-end">
                                <div className="text-right">
                                    <div className="text-xl font-black text-indigo-600">{agreements.length}</div>
                                    <div className="text-[10px] font-bold text-gray-400 uppercase tracking-widest">Total</div>
                                </div>
                                <div className="text-right">
                                    <div className="text-xl font-black text-green-600">{finalizedCount}</div>
                                    <div className="text-[10px] font-bold text-gray-400 uppercase tracking-widest">Finalized</div>
                                </div>
                                <div className="text-right">
                                    <div className="text-xl font-black text-amber-600">{draftCount}</div>
                                    <div className="text-[10px] font-bold text-gray-400 uppercase tracking-widest">Drafts</div>
                                </div>
                            </div>
                        </div>

                        {/* Upload Card */}
                        <div className="bg-white rounded-2xl border border-gray-200 shadow-sm overflow-hidden">
                            <div className="px-8 py-6 border-b border-gray-100 bg-gray-50/50 flex items-center gap-4">
                                <div className="w-10 h-10 bg-indigo-600 rounded-xl flex items-center justify-center text-white shadow-lg shadow-indigo-600/20">
                                    <i className="fas fa-cloud-upload-alt" />
                                </div>
                                <div>
                                    <h3 className="text-lg font-bold text-gray-900 tracking-tight">Upload New Agreement</h3>
                                    <p className="text-xs text-gray-500 font-medium uppercase tracking-wider mt-0.5">Files are initially uploaded as drafts</p>
                                </div>
                            </div>

                            <div className="p-8">
                                <div className="flex flex-col lg:flex-row items-stretch lg:items-center gap-8">
                                    <div className="flex-1 relative group">
                                        <label htmlFor="agreement-file" className="block text-[10px] font-black text-gray-400 uppercase tracking-widest mb-3">
                                            Select Document (PDF/Doc)
                                        </label>
                                        <div className="relative">
                                            <input
                                                id="agreement-file"
                                                type="file"
                                                ref={fileInputRef}
                                                onChange={handleFileSelect}
                                                accept=".pdf,.doc,.docx"
                                                className="absolute inset-0 opacity-0 cursor-pointer z-10 h-full w-full"
                                            />
                                            <div className="w-full px-6 py-4 bg-gray-50 border-2 border-dashed border-gray-200 rounded-2xl flex items-center justify-between transition-all group-hover:border-indigo-400 group-hover:bg-indigo-50/30">
                                                <div className="flex items-center gap-4 overflow-hidden">
                                                    <div className="w-10 h-10 bg-white rounded-xl border border-gray-100 flex items-center justify-center text-lg shadow-sm">
                                                        <i className={selectedFile ? getFileIcon(selectedFile.name) : "fas fa-file-upload text-gray-300"} />
                                                    </div>
                                                    <span className={`text-sm font-medium truncate ${selectedFile ? 'text-gray-900' : 'text-gray-400'}`}>
                                                        {selectedFile ? selectedFile.name : 'Choose a file to upload...'}
                                                    </span>
                                                </div>
                                                <div className="px-3 py-1 bg-white border border-gray-100 rounded-lg text-[10px] font-bold text-gray-500 uppercase tracking-wider shadow-sm">
                                                    Browse Files
                                                </div>
                                            </div>
                                        </div>
                                    </div>

                                    <div className="lg:w-64 pt-6 flex flex-col justify-end">
                                        <button
                                            onClick={handleUploadClick}
                                            disabled={!selectedFile || uploading}
                                            className="w-full flex items-center justify-center gap-3 px-8 py-4 bg-indigo-600 text-white rounded-2xl font-bold shadow-xl shadow-indigo-500/20 hover:bg-indigo-700 disabled:opacity-50 disabled:cursor-not-allowed transition-all active:scale-95 translate-y-2 lg:translate-y-1.5"
                                        >
                                            {uploading ? (
                                                <>
                                                    <i className="fas fa-spinner fa-spin" />
                                                    <span>Uploading...</span>
                                                </>
                                            ) : (
                                                <>
                                                    <i className="fas fa-arrow-up-from-bracket" />
                                                    <span>Upload Draft</span>
                                                </>
                                            )}
                                        </button>
                                    </div>
                                </div>

                                {/* Feedback Messages */}
                                {(uploadError || uploadSuccess) && (
                                    <div className="mt-6 flex flex-col gap-3">
                                        {uploadError && (
                                            <div className="bg-red-50 text-red-700 px-4 py-3 rounded-xl border-l-4 border-red-500 text-sm font-medium animate-shake flex items-center gap-3">
                                                <i className="fas fa-times-circle" />
                                                <span>{uploadError}</span>
                                            </div>
                                        )}
                                        {uploadSuccess && (
                                            <div className="bg-green-50 text-green-700 px-4 py-3 rounded-xl border-l-4 border-green-500 text-sm font-medium animate-fade-in flex items-center gap-3">
                                                <i className="fas fa-check-circle" />
                                                <span>File uploaded successfully!</span>
                                            </div>
                                        )}
                                    </div>
                                )}
                            </div>
                        </div>

                        {/* Agreements Table Card */}
                        <div className="bg-white rounded-2xl border border-gray-200 shadow-sm overflow-hidden">
                            <div className="px-8 py-6 border-b border-gray-100 bg-gray-50/50">
                                <h3 className="text-lg font-bold text-gray-900 tracking-tight">Agreement Registry</h3>
                                <p className="text-xs text-gray-500 font-medium uppercase tracking-wider mt-0.5">Manage and review all contract versions</p>
                            </div>

                            {/* Loading State */}
                            {loading && (
                                <div className="flex flex-col items-center justify-center py-20">
                                    <div className="w-12 h-12 border-4 border-indigo-100 border-t-indigo-600 rounded-full animate-spin mb-4" />
                                    <p className="text-gray-500 font-bold uppercase tracking-widest text-xs">Loading records...</p>
                                </div>
                            )}

                            {/* Error State */}
                            {error && !loading && (
                                <div className="p-20 text-center">
                                    <div className="w-16 h-16 bg-red-50 rounded-2xl flex items-center justify-center mx-auto mb-4">
                                        <i className="fas fa-exclamation-triangle text-2xl text-red-500" />
                                    </div>
                                    <h4 className="text-lg font-bold text-gray-900">Failed to load agreements</h4>
                                    <p className="text-sm text-gray-500 font-medium max-w-sm mx-auto mt-2 leading-relaxed">{error}</p>
                                </div>
                            )}

                            {/* Table */}
                            {!loading && !error && (
                                <>
                                    {agreements.length > 0 ? (
                                        <div className="overflow-x-auto overflow-y-hidden custom-scrollbar">
                                            <table className="w-full text-left border-collapse">
                                                <thead>
                                                    <tr className="bg-gray-50/30 border-b border-gray-100">
                                                        <th className="px-8 py-4 text-[10px] font-black text-gray-400 uppercase tracking-widest">Document</th>
                                                        <th className="px-8 py-4 text-[10px] font-black text-gray-400 uppercase tracking-widest">Metadata</th>
                                                        <th className="px-8 py-4 text-[10px] font-black text-gray-400 uppercase tracking-widest text-center">Status</th>
                                                        <th className="px-8 py-4 text-[10px] font-black text-gray-400 uppercase tracking-widest text-right">Actions</th>
                                                    </tr>
                                                </thead>
                                                <tbody className="divide-y divide-gray-100">
                                                    {agreements.map((agreement: Agreement) => (
                                                        <tr key={agreement.id} className="group hover:bg-gray-50/80 transition-all duration-200">
                                                            <td className="px-8 py-5">
                                                                <div className="flex items-center gap-4">
                                                                    <div className="w-10 h-10 rounded-xl bg-gray-50 border border-gray-100 flex items-center justify-center text-lg shadow-sm transition-transform group-hover:scale-110">
                                                                        <i className={getFileIcon(agreement.fileName)} />
                                                                    </div>
                                                                    <div>
                                                                        <div className="text-sm font-bold text-gray-900 group-hover:text-indigo-600 transition-colors line-clamp-1">{agreement.fileName}</div>
                                                                        <div className="text-[10px] text-gray-400 font-bold uppercase tracking-wider mt-0.5">ID: #{agreement.id}</div>
                                                                    </div>
                                                                </div>
                                                            </td>
                                                            <td className="px-8 py-5">
                                                                <div className="space-y-1">
                                                                    <div className="flex items-center gap-2 text-xs font-bold text-gray-700">
                                                                        <i className="far fa-clock text-gray-300" />
                                                                        <span>{formatDate(agreement.uploadedAt)}</span>
                                                                    </div>
                                                                    <div className="flex items-center gap-2 text-[10px] font-bold text-gray-400 uppercase">
                                                                        <i className="fas fa-user-edit" />
                                                                        <span>{agreement.uploadedBy || 'System Admin'}</span>
                                                                    </div>
                                                                </div>
                                                            </td>
                                                            <td className="px-8 py-5 text-center">
                                                                <span className={`inline-flex items-center gap-2 px-3 py-1 rounded-full text-[10px] font-bold tracking-wider uppercase border shadow-sm ${agreement.status === 'FINALIZED'
                                                                    ? 'bg-green-50 text-green-700 border-green-100'
                                                                    : 'bg-amber-50 text-amber-700 border-amber-100 animate-pulse'
                                                                    }`}>
                                                                    <i className={agreement.status === 'FINALIZED' ? 'fas fa-check-circle' : 'fas fa-pen-fancy'} />
                                                                    <span>{agreement.status}</span>
                                                                </span>
                                                            </td>
                                                            <td className="px-8 py-5 text-right whitespace-nowrap">
                                                                <div className="flex items-center justify-end gap-2">
                                                                    {/* View Button */}
                                                                    <a
                                                                        href={agreement.downloadUrl}
                                                                        target="_blank"
                                                                        rel="noopener noreferrer"
                                                                        className="w-10 h-10 bg-white border border-gray-100 rounded-xl flex items-center justify-center text-gray-500 hover:text-indigo-600 hover:border-indigo-200 hover:shadow-md transition-all active:scale-90"
                                                                        title="View/Download"
                                                                    >
                                                                        <i className="fas fa-eye" />
                                                                    </a>

                                                                    {/* Finalize Button - Only for DRAFT */}
                                                                    {agreement.status === 'DRAFT' && (
                                                                        <button
                                                                            onClick={() => requestFinalize(agreement)}
                                                                            className="w-10 h-10 bg-white border border-gray-100 rounded-xl flex items-center justify-center text-gray-500 hover:text-green-600 hover:border-green-200 hover:shadow-md transition-all active:scale-90"
                                                                            title="Finalize to Client"
                                                                        >
                                                                            <i className="fas fa-check-circle" />
                                                                        </button>
                                                                    )}

                                                                    {/* Delete Button */}
                                                                    <button
                                                                        onClick={() => requestDelete(agreement)}
                                                                        className="w-10 h-10 bg-white border border-gray-100 rounded-xl flex items-center justify-center text-gray-500 hover:text-red-600 hover:border-red-200 hover:shadow-md transition-all active:scale-90"
                                                                        title="Delete Permanent"
                                                                    >
                                                                        <i className="fas fa-trash" />
                                                                    </button>
                                                                </div>
                                                            </td>
                                                        </tr>
                                                    ))}
                                                </tbody>
                                            </table>
                                        </div>
                                    ) : (
                                        <div className="py-20 text-center">
                                            <div className="w-16 h-16 bg-gray-50 rounded-2xl flex items-center justify-center mx-auto mb-4 border border-gray-100">
                                                <i className="fas fa-folder-open text-2xl text-gray-300" />
                                            </div>
                                            <h4 className="text-lg font-bold text-gray-900">Repository is empty</h4>
                                            <p className="text-sm text-gray-500 font-medium max-w-sm mx-auto mt-2 leading-relaxed">
                                                No agreements have been uploaded for this account yet. Use the upload tool above to start.
                                            </p>
                                        </div>
                                    )}
                                </>
                            )}
                        </div>
                    </div>
                )}
            </main>
        </div>
    );
};

export default BillOpsAdminAgreementsPage;
