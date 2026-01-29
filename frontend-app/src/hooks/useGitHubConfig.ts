/**
 * useGitHubConfig Hook
 *
 * Custom hook for managing GitHub repository configuration
 * Converted from: add-github-config.html (Alpine.js addGitHubConfig() function)
 *
 * What changed:
 * - Migrated from Alpine.js to React with TypeScript
 * - Added proper type safety
 * - Used React hooks for state management
 *
 * What stayed the same:
 * - API endpoint remains identical: POST /api/cicd/config/github
 * - Same business logic preserved exactly
 * - Same validation and error handling
 */

import { useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { apiFetch } from '../utils/apiClient';
import type { GitHubDetails } from '../types/addAccount';

interface UseGitHubConfigReturn {
  // State
  githubDetails: GitHubDetails;
  isLoading: boolean;
  error: string;

  // Setters
  setGitHubDetails: (details: Partial<GitHubDetails>) => void;
  clearError: () => void;

  // Actions
  connectGitHub: () => Promise<void>;
}

export function useGitHubConfig(): UseGitHubConfigReturn {
  const navigate = useNavigate();

  // State
  const [githubDetails, setGitHubDetailsState] = useState<GitHubDetails>({
    owner: '',
    repo: '',
    pat: '',
  });
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState('');

  /**
   * Update GitHub details partially
   */
  const setGitHubDetails = useCallback((details: Partial<GitHubDetails>) => {
    setGitHubDetailsState(prev => ({ ...prev, ...details }));
  }, []);

  /**
   * Clear error message
   */
  const clearError = useCallback(() => {
    setError('');
  }, []);

  /**
   * Connect GitHub repository
   * API: POST /api/cicd/config/github
   */
  const connectGitHub = useCallback(async () => {
    setIsLoading(true);
    setError('');

    // Validate all fields are filled
    if (!githubDetails.owner.trim() || !githubDetails.repo.trim() || !githubDetails.pat.trim()) {
      setError('Please fill in all fields.');
      setIsLoading(false);
      return;
    }

    try {
      const res = await apiFetch('/api/cicd/config/github', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          owner: githubDetails.owner.trim(),
          repo: githubDetails.repo.trim(),
          pat: githubDetails.pat.trim(),
        }),
      });

      if (!res.ok) {
        // Try to parse error message from response
        try {
          const errData = await res.json();
          throw new Error(errData.message || 'Connection failed. Please check details and PAT permissions.');
        } catch {
          // If JSON parsing fails, check for auth errors
          if (res.status === 401 || res.status === 403) {
            throw new Error('Authentication failed. Please log in and try again.');
          }
          throw new Error(`Connection failed with status: ${res.status}`);
        }
      }

      // Success - show message and redirect
      alert('GitHub repository connected successfully! Redirecting...');
      navigate('/account-manager');
    } catch (err) {
      console.error('Error connecting GitHub account:', err);
      setError(err instanceof Error ? err.message : 'An unknown error occurred.');
    } finally {
      setIsLoading(false);
    }
  }, [githubDetails, navigate]);

  return {
    // State
    githubDetails,
    isLoading,
    error,

    // Setters
    setGitHubDetails,
    clearError,

    // Actions
    connectGitHub,
  };
}
