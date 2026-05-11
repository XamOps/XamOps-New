import React, { useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { motion } from 'framer-motion';
import {
  FolderGit2, KeyRound, User, CheckCircle2, Loader2,
  AlertCircle, ShieldCheck, ExternalLink, Cpu, GitBranch,
  Activity, Bell, BarChart3, GitMerge,
} from 'lucide-react';
import { Button } from '../ui/button';
import { Input } from '../ui/input';
import { Label } from '../ui/label';
import { Card, CardContent } from '../ui/card';
import { Separator } from '../ui/separator';
import { apiFetch } from '../../utils/apiClient';
import type { GitHubDetails } from '../../types/addAccount';

const features = [
  { icon: Activity, label: 'Real-time workflow status' },
  { icon: Bell, label: 'Build success/failure alerts' },
  { icon: BarChart3, label: 'CI/CD pipeline monitoring' },
  { icon: GitMerge, label: 'XamOps dashboard integration' },
];

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

  // ── Success screen ─────────────────────────────────────────────────────
  if (success) {
    return (
      <motion.div initial={{ opacity: 0, scale: 0.97 }} animate={{ opacity: 1, scale: 1 }}>
        <Card className="shadow-sm border-emerald-200 overflow-hidden">
          <div className="h-1 bg-gradient-to-r from-slate-700 to-slate-900" />
          <CardContent className="p-10 flex flex-col items-center text-center">
            <motion.div
              initial={{ scale: 0 }}
              animate={{ scale: 1 }}
              transition={{ type: 'spring', stiffness: 300, damping: 20, delay: 0.1 }}
              className="w-24 h-24 rounded-full bg-gradient-to-br from-slate-700 to-slate-900 flex items-center justify-center mb-6 shadow-2xl shadow-slate-800/30 ring-8 ring-slate-100"
            >
              <CheckCircle2 className="w-12 h-12 text-white" strokeWidth={2} />
            </motion.div>
            <h2 className="text-2xl font-bold text-slate-900 mb-2 tracking-tight">GitHub Connected!</h2>
            <div className="flex items-center gap-2 px-4 py-2 bg-slate-100 border border-slate-300 rounded-full mb-4">
              <i className="fab fa-github text-slate-800 text-sm" />
              <span className="text-sm font-semibold text-slate-800">{details.owner}/{details.repo}</span>
            </div>
            <p className="text-slate-500 text-sm max-w-sm leading-relaxed mb-8">
              Your GitHub repository has been connected to XamOps. You can now monitor GitHub Actions workflows from your dashboard.
            </p>
            <Button variant="success" size="lg" onClick={() => navigate('/account-manager')} className="gap-2">
              <Cpu className="w-4 h-4" />
              Go to Account Manager
            </Button>
          </CardContent>
        </Card>
      </motion.div>
    );
  }

  return (
    <motion.div initial={{ opacity: 0, y: 16 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.4 }} className="space-y-4">

      {/* Security + Features info */}
      <Card className="shadow-sm border-slate-200 overflow-hidden">
        <div className="h-1 bg-gradient-to-r from-slate-700 to-slate-900" />
        <CardContent className="p-5">
          <div className="flex gap-4 items-start">
            {/* Security notice */}
            <div className="flex items-start gap-3 flex-1 p-3.5 bg-emerald-50 border border-emerald-200 rounded-xl">
              <ShieldCheck className="w-4 h-4 text-emerald-600 flex-shrink-0 mt-0.5" />
              <div>
                <p className="text-xs font-semibold text-emerald-800 mb-0.5">Secure Storage</p>
                <p className="text-xs text-emerald-700 leading-relaxed">Your Personal Access Token is stored securely encrypted. Use minimal required permissions.</p>
              </div>
            </div>
            {/* Features */}
            <div className="flex-1 grid grid-cols-2 gap-2">
              {features.map(({ icon: Icon, label }) => (
                <div key={label} className="flex items-center gap-1.5 text-xs text-slate-600 bg-slate-50 border border-slate-200 px-2.5 py-2 rounded-lg">
                  <Icon className="w-3 h-3 text-slate-500 flex-shrink-0" />
                  <span>{label}</span>
                </div>
              ))}
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Main form card */}
      <Card className="shadow-sm border-slate-200 overflow-hidden">
        <div className="h-1 bg-gradient-to-r from-slate-700 to-slate-900" />
        <CardContent className="p-6">
          <div className="flex items-start gap-4 mb-6">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-slate-700 to-slate-900 text-white flex items-center justify-center flex-shrink-0 shadow-lg shadow-slate-800/25 text-sm font-bold">2</div>
            <div>
              <h2 className="text-lg font-bold text-slate-900 tracking-tight">Repository Details</h2>
              <p className="text-sm text-slate-500">
                Provide a PAT with <code className="bg-slate-100 px-1.5 py-0.5 rounded text-xs font-mono">repo:status</code> or full <code className="bg-slate-100 px-1.5 py-0.5 rounded text-xs font-mono">repo</code> scope
              </p>
            </div>
          </div>

          <form onSubmit={handleSubmit} className="space-y-5">
            {/* Owner */}
            <div className="space-y-2">
              <Label htmlFor="githubOwner" className="flex items-center gap-1.5">
                <User className="w-3.5 h-3.5 text-slate-500" />
                GitHub Owner
              </Label>
              <Input
                id="githubOwner"
                placeholder="e.g. my-organization"
                value={details.owner}
                onChange={(e) => setDetails({ owner: e.target.value })}
                disabled={isLoading}
                required
              />
              <p className="text-xs text-slate-400">GitHub username or organization name that owns the repository</p>
            </div>

            {/* Repo */}
            <div className="space-y-2">
              <Label htmlFor="githubRepo" className="flex items-center gap-1.5">
                <FolderGit2 className="w-3.5 h-3.5 text-slate-500" />
                Repository Name
              </Label>
              <Input
                id="githubRepo"
                placeholder="e.g. my-awesome-project"
                value={details.repo}
                onChange={(e) => setDetails({ repo: e.target.value })}
                disabled={isLoading}
                required
              />
              <p className="text-xs text-slate-400">The name of the repository you want to monitor</p>
            </div>

            {/* PAT */}
            <div className="space-y-2">
              <Label htmlFor="githubPat" className="flex items-center gap-1.5">
                <KeyRound className="w-3.5 h-3.5 text-slate-500" />
                Personal Access Token (PAT)
              </Label>
              <Input
                type="password"
                id="githubPat"
                placeholder="ghp_xxxxxxxxxxxxxxxxxxxx"
                value={details.pat}
                onChange={(e) => setDetails({ pat: e.target.value })}
                disabled={isLoading}
                required
              />
              <p className="text-xs text-slate-400">Your token will be stored securely encrypted</p>
            </div>

            {/* How to create PAT */}
            <div className="p-4 bg-slate-50 border border-slate-200 rounded-xl space-y-2">
              <p className="text-xs font-semibold text-slate-700 flex items-center gap-1.5">
                <GitBranch className="w-3.5 h-3.5" />
                How to create a Personal Access Token
              </p>
              <ol className="space-y-1 text-xs text-slate-600 pl-4">
                <li>1. Go to GitHub <strong>Settings → Developer settings → Personal access tokens</strong></li>
                <li>2. Click <strong>"Generate new token (classic)"</strong></li>
                <li>3. Give it a name like <em>"XamOps Integration"</em></li>
                <li>4. Select <code className="bg-white border border-slate-200 px-1 rounded">repo:status</code> scope (or full <code className="bg-white border border-slate-200 px-1 rounded">repo</code> for private repos)</li>
                <li>5. Click <strong>"Generate token"</strong> and copy it immediately</li>
              </ol>
              <a
                href="https://github.com/settings/tokens/new"
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex items-center gap-1 text-xs text-slate-600 font-semibold hover:text-slate-900 underline underline-offset-2 mt-1"
              >
                Open GitHub Token Settings <ExternalLink className="w-3 h-3" />
              </a>
            </div>

            {/* Error */}
            {error && (
              <div className="flex items-start gap-3 p-4 bg-red-50 border border-red-200 rounded-xl">
                <AlertCircle className="w-4 h-4 text-red-500 flex-shrink-0 mt-0.5" />
                <p className="text-sm text-red-700">{error}</p>
              </div>
            )}

            <Separator />

            <div className="flex justify-end">
              <Button
                type="submit"
                disabled={isLoading}
                className="gap-2 bg-gradient-to-r from-slate-700 to-slate-900 hover:from-slate-800 hover:to-black shadow-lg shadow-slate-800/25"
              >
                {isLoading ? (
                  <><Loader2 className="w-4 h-4 animate-spin" />Connecting...</>
                ) : (
                  <><i className="fab fa-github text-sm" />Connect GitHub Repository</>
                )}
              </Button>
            </div>
          </form>
        </CardContent>
      </Card>
    </motion.div>
  );
};

export default GitHubForm;
