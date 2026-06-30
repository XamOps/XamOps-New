import React, { useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icon } from '@iconify/react';
import { apiFetch } from '../../utils/apiClient';
import type { GitHubDetails } from '../../types/addAccount';

const inputCls = 'w-full px-3.5 py-2.5 text-[13px] border border-[rgba(0,0,0,0.12)] rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-black/5 focus:border-[rgba(0,0,0,0.25)] transition-colors placeholder:text-[rgba(0,0,0,0.3)]';
const labelCls = 'block text-[13px] font-medium text-[rgba(0,0,0,0.7)] mb-1.5';
const hintCls = 'text-[11px] text-[rgba(0,0,0,0.4)] mt-1';

const GitHubForm: React.FC = () => {
  const navigate = useNavigate();

  const [details, setDetailsState] = useState<GitHubDetails>({ owner: '', repo: '', pat: '' });
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState(false);

  const setDetails = useCallback((partial: Partial<GitHubDetails>) => {
    setDetailsState(prev => ({ ...prev, ...partial }));
  }, []);

  const handleSubmit = useCallback(async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');

    if (!details.owner.trim() || !details.repo.trim() || !details.pat.trim()) {
      setError('Please fill in all required fields.');
      return;
    }

    setIsLoading(true);
    try {
      const res = await apiFetch('/api/cicd/config/github', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          owner: details.owner.trim(),
          repo: details.repo.trim(),
          pat: details.pat.trim(),
        }),
      });

      if (!res.ok) {
        try {
          const errData = await res.json();
          throw new Error(errData.message || 'Connection failed. Please check your details and PAT permissions.');
        } catch {
          if (res.status === 401 || res.status === 403) throw new Error('Authentication failed. Please check your PAT.');
          throw new Error(`Connection failed with status: ${res.status}`);
        }
      }

      setSuccess(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An unknown error occurred.');
    } finally {
      setIsLoading(false);
    }
  }, [details]);

  // ── Success screen ──
  if (success) {
    return (
      <div className="max-w-lg mx-auto py-16 text-center">
        <div className="w-14 h-14 rounded-full bg-emerald-50 border border-emerald-200 flex items-center justify-center mx-auto mb-4">
          <Icon icon="mdi:check" width={28} className="text-emerald-600" />
        </div>
        <h2 className="text-[18px] font-bold text-black mb-2">GitHub Connected</h2>
        <p className="text-[13px] text-[rgba(0,0,0,0.5)] mb-6">
          {details.owner}/{details.repo} has been connected to XamOps.
        </p>
        <button
          onClick={() => navigate('/account-manager')}
          className="inline-flex items-center gap-2 px-5 py-2.5 bg-[#181717] text-white text-[13px] font-medium rounded-lg hover:bg-[#333] transition-colors"
        >
          Go to Account Manager
          <Icon icon="mdi:arrow-right" width={16} />
        </button>
      </div>
    );
  }

  return (
    <div>
      {/* Header */}
      <div className="flex items-center gap-3 mb-6">
        <div className="w-10 h-10 rounded-xl bg-[#181717]/10 border border-[#181717]/20 flex items-center justify-center">
          <Icon icon="mdi:github" width={22} className="text-[#181717]" />
        </div>
        <div>
          <h2 className="text-[16px] font-bold text-black">Connect GitHub Repository</h2>
          <p className="text-[12px] text-[rgba(0,0,0,0.45)]">Provide a PAT with <code className="bg-[rgba(0,0,0,0.04)] px-1 rounded text-[11px]">repo:status</code> or full <code className="bg-[rgba(0,0,0,0.04)] px-1 rounded text-[11px]">repo</code> scope.</p>
        </div>
      </div>

      <form onSubmit={handleSubmit} className="space-y-5">
        {/* Owner */}
        <div>
          <label htmlFor="githubOwner" className={labelCls}>GitHub Owner</label>
          <input
            id="githubOwner"
            type="text"
            placeholder="e.g. my-organization"
            value={details.owner}
            onChange={(e) => setDetails({ owner: e.target.value })}
            disabled={isLoading}
            className={inputCls}
            required
          />
          <p className={hintCls}>GitHub username or organization that owns the repository</p>
        </div>

        {/* Repo */}
        <div>
          <label htmlFor="githubRepo" className={labelCls}>Repository Name</label>
          <input
            id="githubRepo"
            type="text"
            placeholder="e.g. my-awesome-project"
            value={details.repo}
            onChange={(e) => setDetails({ repo: e.target.value })}
            disabled={isLoading}
            className={inputCls}
            required
          />
          <p className={hintCls}>The name of the repository you want to monitor</p>
        </div>

        {/* PAT */}
        <div>
          <label htmlFor="githubPat" className={labelCls}>Personal Access Token (PAT)</label>
          <input
            type="password"
            id="githubPat"
            placeholder="ghp_xxxxxxxxxxxxxxxxxxxx"
            value={details.pat}
            onChange={(e) => setDetails({ pat: e.target.value })}
            disabled={isLoading}
            className={inputCls}
            required
          />
          <p className={hintCls}>Your token is stored securely encrypted</p>
        </div>

        {/* How to create PAT */}
        <div className="p-4 bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.06)] rounded-lg">
          <p className="text-[12px] font-medium text-[rgba(0,0,0,0.6)] mb-2 flex items-center gap-1.5">
            <Icon icon="mdi:information-outline" width={14} />
            How to create a Personal Access Token
          </p>
          <ol className="space-y-1 text-[11px] text-[rgba(0,0,0,0.5)] pl-3">
            <li>1. Go to GitHub Settings → Developer settings → Personal access tokens</li>
            <li>2. Click "Generate new token (classic)"</li>
            <li>3. Select <code className="bg-[rgba(0,0,0,0.04)] px-1 rounded">repo:status</code> scope (or full <code className="bg-[rgba(0,0,0,0.04)] px-1 rounded">repo</code> for private repos)</li>
            <li>4. Click "Generate token" and copy it immediately</li>
          </ol>
          <a
            href="https://github.com/settings/tokens/new"
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-1 text-[11px] text-[#181717] font-medium hover:underline mt-2"
          >
            Open GitHub Token Settings <Icon icon="mdi:open-in-new" width={11} />
          </a>
        </div>

        {/* Error */}
        {error && (
          <div className="flex items-center gap-2 px-3 py-2.5 bg-red-50 border border-red-100 rounded-lg">
            <Icon icon="mdi:alert-circle-outline" width={16} className="text-red-500 flex-shrink-0" />
            <span className="text-[12px] text-red-700">{error}</span>
          </div>
        )}

        {/* Submit */}
        <button
          type="submit"
          disabled={isLoading}
          className="w-full py-3 bg-[#181717] text-white text-[13px] font-semibold rounded-lg hover:bg-[#333] transition-colors disabled:opacity-40 flex items-center justify-center gap-2 shadow-sm shadow-[#181717]/20"
        >
          {isLoading ? (
            <><div className="w-3.5 h-3.5 border-2 border-white border-t-transparent rounded-full animate-spin" />Connecting...</>
          ) : (
            <>Connect GitHub Repository <Icon icon="mdi:github" width={16} /></>
          )}
        </button>
      </form>
    </div>
  );
};

export default GitHubForm;
