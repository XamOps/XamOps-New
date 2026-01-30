/**
 * useAdminAgreements Hook
 *
 * Custom hook for admin agreement operations (upload, finalize, delete)
 * Converted from: billops/admin_agreements.html
 * 
 * Features:
 * - All features from useAgreements (fetch, account selection)
 * - File upload with progress state
 * - Finalize draft agreements
 * - Delete agreements with confirmation
 *
 * What stayed the same:
 * - Same API endpoints as original HTML
 * - Same data structure
 * - Same business logic
 *
 * What changed:
 * - Migrated from Alpine.js to React with TypeScript
 * - Uses React state management
 * - Proper TypeScript types
 */

import { useState, useCallback } from 'react';
import { useAgreements } from './useAgreements';
import type { Agreement, UseAdminAgreementsResult } from '../types/agreements';

export const useAdminAgreements = (): UseAdminAgreementsResult => {
    // Get base agreements functionality
    const baseHook = useAgreements();

    // Upload state
    const [selectedFile, setSelectedFile] = useState<File | null>(null);
    const [uploading, setUploading] = useState<boolean>(false);
    const [uploadError, setUploadError] = useState<string | null>(null);
    const [uploadSuccess, setUploadSuccess] = useState<boolean>(false);

    /**
     * Handle file selection from input
     */
    const handleFileSelect = useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
        const file = event.target.files?.[0] || null;
        setSelectedFile(file);
        setUploadError(null);
        setUploadSuccess(false);
    }, []);

    /**
     * Clear upload state
     */
    const clearUploadState = useCallback(() => {
        setSelectedFile(null);
        setUploadError(null);
        setUploadSuccess(false);
    }, []);

    /**
     * Upload a new agreement as DRAFT
     */
    const uploadAgreement = useCallback(async () => {
        if (!selectedFile || !baseHook.accountId) {
            setUploadError('Please select a file and ensure an account is selected.');
            return;
        }

        setUploading(true);
        setUploadError(null);
        setUploadSuccess(false);

        console.log(`[AdminAgreements] Uploading file: ${selectedFile.name} for account: ${baseHook.accountId}`);

        try {
            const formData = new FormData();
            formData.append('file', selectedFile);
            formData.append('accountId', baseHook.accountId);

            const response = await fetch('/api/billops/agreements/upload', {
                method: 'POST',
                credentials: 'include',
                body: formData,
                // Note: Do NOT set Content-Type header for FormData, browser handles it
            });

            if (!response.ok) {
                const errorText = await response.text();
                throw new Error(errorText || 'Upload failed');
            }

            const data = await response.json();
            console.log('[AdminAgreements] Upload successful:', data);

            setUploadSuccess(true);
            setSelectedFile(null);

            // Refresh agreements list
            await baseHook.fetchAgreements();

            // Clear success message after 3 seconds
            setTimeout(() => setUploadSuccess(false), 3000);
        } catch (err) {
            console.error('[AdminAgreements] Upload error:', err);
            setUploadError(`Upload failed: ${err instanceof Error ? err.message : 'Unknown error'}`);
        } finally {
            setUploading(false);
        }
    }, [selectedFile, baseHook.accountId, baseHook.fetchAgreements]);

    /**
     * Finalize a DRAFT agreement (makes it visible to users)
     */
    const finalizeAgreement = useCallback(async (agreement: Agreement) => {
        const confirmed = window.confirm(
            `Are you sure you want to finalize "${agreement.fileName}"? This will make it visible to the user.`
        );

        if (!confirmed) return;

        console.log(`[AdminAgreements] Finalizing agreement: ${agreement.id}`);

        try {
            const response = await fetch(`/api/billops/agreements/${agreement.id}/finalize`, {
                method: 'PUT',
                credentials: 'include',
                headers: {
                    'Accept': 'application/json',
                },
            });

            if (!response.ok) {
                throw new Error('Failed to finalize agreement');
            }

            console.log('[AdminAgreements] Finalize successful');

            // Refresh agreements list
            await baseHook.fetchAgreements();
        } catch (err) {
            console.error('[AdminAgreements] Finalize error:', err);
            alert(err instanceof Error ? err.message : 'Failed to finalize agreement');
        }
    }, [baseHook.fetchAgreements]);

    /**
     * Delete an agreement
     */
    const deleteAgreement = useCallback(async (agreement: Agreement) => {
        const confirmed = window.confirm(
            `Delete "${agreement.fileName}"? This cannot be undone.`
        );

        if (!confirmed) return;

        console.log(`[AdminAgreements] Deleting agreement: ${agreement.id}`);

        try {
            const response = await fetch(`/api/billops/agreements/${agreement.id}`, {
                method: 'DELETE',
                credentials: 'include',
            });

            if (!response.ok) {
                throw new Error('Failed to delete agreement');
            }

            console.log('[AdminAgreements] Delete successful');

            // Refresh agreements list
            await baseHook.fetchAgreements();
        } catch (err) {
            console.error('[AdminAgreements] Delete error:', err);
            alert(err instanceof Error ? err.message : 'Failed to delete agreement');
        }
    }, [baseHook.fetchAgreements]);

    return {
        // Base hook properties
        ...baseHook,

        // Upload state
        selectedFile,
        uploading,
        uploadError,
        uploadSuccess,

        // Admin operations
        handleFileSelect,
        uploadAgreement,
        finalizeAgreement,
        deleteAgreement,
        clearUploadState,
    };
};

export default useAdminAgreements;
