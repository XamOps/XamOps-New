import React, { useState, useCallback, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { motion, AnimatePresence } from 'framer-motion';
import {
  Tag, FolderOpen, Key, Database, Copy, Check, Loader2,
  CheckCircle2, ChevronDown, ExternalLink, Terminal,
  BarChart3, Package, Activity, Cpu, AlertCircle,
} from 'lucide-react';
import { cn } from '../../lib/utils';
import { Button } from '../ui/button';
import { Input } from '../ui/input';
import { Label } from '../ui/label';
import { Textarea } from '../ui/textarea';
import { Card, CardContent } from '../ui/card';
import { Badge } from '../ui/badge';
import { Separator } from '../ui/separator';
import { apiFetch } from '../../utils/apiClient';

const SCRIPT_URL = 'https://xamops-gcp-sa.s3.ap-south-1.amazonaws.com/create_serviceaccount.sh';

const steps = [
  { num: 1, title: 'Create Service Account', desc: 'Run script in Cloud Shell' },
  { num: 2, title: 'Enable Billing Export', desc: 'Configure BigQuery export' },
  { num: 3, title: 'Grant Permissions', desc: 'Authorize service account' },
  { num: 4, title: 'Enter Details', desc: 'Paste credentials & connect' },
];

const GCPForm: React.FC = () => {
  const navigate = useNavigate();

  const [accountName, setAccountName] = useState('');
  const [projectId, setProjectId] = useState('');
  const [serviceAccountKey, setServiceAccountKey] = useState('');
  const [billingTable, setBillingTable] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [commandText, setCommandText] = useState(`sh <(curl -s ${SCRIPT_URL}) YOUR_PROJECT_ID`);
  const [copiedCmd, setCopiedCmd] = useState(false);
  const [showGuide, setShowGuide] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState(false);

  const step1Ref = useRef<HTMLDivElement>(null);
  const step2Ref = useRef<HTMLDivElement>(null);
  const step3Ref = useRef<HTMLDivElement>(null);
  const step4Ref = useRef<HTMLDivElement>(null);
  const stepRefs = [step1Ref, step2Ref, step3Ref, step4Ref];

  const scrollToStep = useCallback((num: number) => {
    const ref = stepRefs[num - 1];
    if (!ref?.current) return;
    if (num <= 3 && !showGuide) setShowGuide(true);
    setTimeout(() => ref.current?.scrollIntoView({ behavior: 'smooth', block: 'start' }), num <= 3 ? 320 : 0);
  }, [showGuide, stepRefs]);

  useEffect(() => {
    const pid = projectId.trim() || 'YOUR_PROJECT_ID';
    setCommandText(`sh <(curl -s ${SCRIPT_URL}) ${pid}`);
  }, [projectId]);

  const handleCopyCmd = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(commandText);
      setCopiedCmd(true);
      setTimeout(() => setCopiedCmd(false), 2000);
    } catch { /* ignore */ }
  }, [commandText]);

  const handleSubmit = useCallback(async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');

    if (!accountName.trim() || !projectId.trim() || !serviceAccountKey.trim()) {
      setError('Please fill in all required fields: Account Name, GCP Project ID, and Service Account Key.');
      return;
    }

    try {
      const parsed = JSON.parse(serviceAccountKey);
      if (!parsed.type || !parsed.project_id || !parsed.private_key || !parsed.client_email) {
        throw new Error('Missing required fields in JSON');
      }
    } catch (e) {
      setError(`Invalid Service Account JSON: ${e instanceof Error ? e.message : 'Unknown error'}`);
      return;
    }

    if (billingTable.trim()) {
      const parts = billingTable.trim().split('.');
      if (parts.length !== 3 || parts.some(p => !p.trim())) {
        setError('BigQuery table must be in format: project.dataset.table');
        return;
      }
    }

    setIsLoading(true);
    try {
      const res = await apiFetch('/api/xamops/account-manager/add-gcp-account', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          accountName: accountName.trim(),
          projectId: projectId.trim(),
          serviceAccountKey: serviceAccountKey.trim(),
          ...(billingTable.trim() && { billingExportTable: billingTable.trim() }),
        }),
      });

      if (res.ok) {
        setSuccess(true);
      } else {
        const errData = await res.json().catch(() => ({}));
        throw new Error(errData.error || errData.message || 'Failed to add account');
      }
    } catch (err) {
      setError(`Failed to add GCP account: ${err instanceof Error ? err.message : 'Unknown error'}`);
    } finally {
      setIsLoading(false);
    }
  }, [accountName, projectId, serviceAccountKey, billingTable]);

  // ── Success screen ──────────────────────────────────────────────────────
  if (success) {
    return (
      <motion.div initial={{ opacity: 0, scale: 0.97 }} animate={{ opacity: 1, scale: 1 }}>
        <Card className="shadow-sm border-emerald-200 overflow-hidden">
          <div className="h-1 bg-gradient-to-r from-blue-500 via-green-400 to-yellow-400" />
          <CardContent className="p-10 flex flex-col items-center text-center">
            <motion.div
              initial={{ scale: 0 }}
              animate={{ scale: 1 }}
              transition={{ type: 'spring', stiffness: 300, damping: 20, delay: 0.1 }}
              className="w-24 h-24 rounded-full bg-gradient-to-br from-blue-500 to-green-500 flex items-center justify-center mb-6 shadow-2xl shadow-blue-500/30 ring-8 ring-blue-100"
            >
              <CheckCircle2 className="w-12 h-12 text-white" strokeWidth={2} />
            </motion.div>
            <h2 className="text-2xl font-bold text-slate-900 mb-2 tracking-tight">GCP Account Connected!</h2>
            <div className="flex items-center gap-2 px-4 py-2 bg-blue-50 border border-blue-200 rounded-full mb-4">
              <i className="fab fa-google text-blue-500" />
              <span className="text-sm font-semibold text-blue-800">{accountName}</span>
            </div>
            <p className="text-slate-500 text-sm max-w-sm leading-relaxed mb-8">
              Your GCP project has been connected to XamOps. You can now start monitoring your cloud resources and billing data.
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

      {/* Setup Overview Card */}
      <Card className="shadow-sm border-slate-200 overflow-hidden">
        <div className="h-1 bg-gradient-to-r from-blue-500 via-green-500 to-yellow-400" />
        <CardContent className="p-6">
          <div className="flex items-start gap-4 mb-5">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-blue-500 to-green-500 text-white flex items-center justify-center flex-shrink-0 shadow-lg shadow-blue-500/25 text-sm font-bold">2</div>
            <div className="flex-1 min-w-0">
              <h2 className="text-lg font-bold text-slate-900 tracking-tight">Google Cloud Setup</h2>
              <p className="text-sm text-slate-500">Connect your GCP project using a Service Account in 4 steps</p>
            </div>
            <div className="flex flex-wrap gap-2 ml-2">
              {[
                { icon: BarChart3, label: 'Cost analytics' },
                { icon: Package, label: 'Resource tracking' },
                { icon: Activity, label: 'Performance' },
              ].map(({ icon: Icon, label }) => (
                <div key={label} className="flex items-center gap-1 text-xs text-slate-500 bg-slate-50 border border-slate-200 px-2 py-1 rounded-lg">
                  <Icon className="w-3 h-3 text-blue-500" />
                  <span>{label}</span>
                </div>
              ))}
            </div>
          </div>

          {/* Steps mini-track — clickable */}
          <div className="grid grid-cols-4 gap-2 mb-5">
            {steps.map((s) => (
              <motion.button
                key={s.num}
                type="button"
                onClick={() => scrollToStep(s.num)}
                className="flex flex-col items-center text-center gap-1 cursor-pointer group"
                whileHover={{ scale: 1.06 }}
                whileTap={{ scale: 0.94 }}
                transition={{ type: 'spring', stiffness: 400, damping: 18 }}
              >
                <motion.div
                  className="w-8 h-8 rounded-full bg-gradient-to-br from-blue-500 to-green-500 text-white text-xs font-bold flex items-center justify-center shadow-sm group-hover:shadow-blue-400/40 group-hover:shadow-md transition-shadow duration-200"
                  whileHover={{ rotate: [0, -10, 8, -5, 0] }}
                  transition={{ duration: 0.4 }}
                >
                  {s.num}
                </motion.div>
                <p className="text-xs font-semibold text-slate-700 leading-tight group-hover:text-blue-600 transition-colors duration-200">{s.title}</p>
                <p className="text-[10px] text-slate-400 leading-tight hidden sm:block">{s.desc}</p>
              </motion.button>
            ))}
          </div>

          {/* Toggle Guide button with animated chevron */}
          <motion.button
            type="button"
            onClick={() => setShowGuide(p => !p)}
            className="w-full flex items-center justify-between px-4 py-3 bg-blue-50 hover:bg-blue-100 border border-blue-200 rounded-xl text-sm font-medium text-blue-700 transition-colors duration-200"
            whileTap={{ scale: 0.98 }}
          >
            <div className="flex items-center gap-2">
              <Terminal className="w-4 h-4" />
              <span>{showGuide ? 'Hide Detailed Setup Guide' : 'View Detailed Setup Guide (Steps 1–3)'}</span>
            </div>
            <motion.div
              animate={{ rotate: showGuide ? 180 : 0 }}
              transition={{ duration: 0.3, ease: 'easeInOut' }}
            >
              <ChevronDown className="w-4 h-4" />
            </motion.div>
          </motion.button>
        </CardContent>
      </Card>

      {/* Expandable Detailed Guide */}
      <AnimatePresence>
        {showGuide && (
          <motion.div
            initial={{ opacity: 0, height: 0 }}
            animate={{ opacity: 1, height: 'auto' }}
            exit={{ opacity: 0, height: 0 }}
            transition={{ duration: 0.3, ease: 'easeInOut' }}
            style={{ overflow: 'hidden' }}
          >
            <Card className="shadow-sm border-slate-200 overflow-hidden">
              <div className="h-1 bg-gradient-to-r from-blue-400 to-green-400" />
              <CardContent className="p-6 space-y-6">

                {/* Step 1: Create Service Account */}
                <div ref={step1Ref}>
                  <div className="flex items-center gap-2 mb-3">
                    <div className="w-6 h-6 rounded-full bg-blue-500 text-white text-xs font-bold flex items-center justify-center">1</div>
                    <h3 className="text-sm font-bold text-slate-900">Create Service Account &amp; Enable APIs</h3>
                    <Badge variant="gcp" className="text-[10px]">Cloud Shell</Badge>
                  </div>
                  <p className="text-xs text-slate-600 mb-3 leading-relaxed">
                    Open <a href="https://shell.cloud.google.com/" target="_blank" rel="noopener noreferrer" className="text-blue-600 font-semibold underline underline-offset-2 inline-flex items-center gap-0.5">Google Cloud Shell <ExternalLink className="w-3 h-3" /></a> and run the command below. It enables 62 APIs and creates a service account automatically.
                  </p>

                  {/* Dynamic command block */}
                  <div className="rounded-xl overflow-hidden border border-slate-800/10">
                    <div className="flex items-center justify-between px-4 py-2 bg-slate-800">
                      <div className="flex items-center gap-2">
                        <div className="flex gap-1.5">
                          <span className="w-2.5 h-2.5 rounded-full bg-red-500/70" />
                          <span className="w-2.5 h-2.5 rounded-full bg-yellow-500/70" />
                          <span className="w-2.5 h-2.5 rounded-full bg-green-500/70" />
                        </div>
                        <span className="text-xs text-slate-400 font-medium ml-1">Cloud Shell — bash</span>
                      </div>
                      <button
                        type="button"
                        onClick={handleCopyCmd}
                        className={cn(
                          'flex items-center gap-1.5 text-xs font-medium px-2.5 py-1 rounded-md border transition-all duration-200',
                          copiedCmd
                            ? 'bg-emerald-500/20 text-emerald-400 border-emerald-500/30'
                            : 'bg-white/8 text-slate-300 border-white/10 hover:bg-white/15 hover:text-white'
                        )}
                      >
                        {copiedCmd ? <><Check className="w-3 h-3" />Copied!</> : <><Copy className="w-3 h-3" />Copy</>}
                      </button>
                    </div>
                    <div className="px-4 py-3.5 bg-slate-900 font-mono text-xs text-slate-300 leading-relaxed overflow-x-auto">
                      <span className="text-green-400">$</span>{' '}
                      <span>sh &lt;(curl -s </span>
                      <span className="text-amber-300">{SCRIPT_URL}</span>
                      <span>){' '}</span>
                      <span className={cn(projectId.trim() ? 'text-sky-300' : 'text-slate-500')}>
                        {projectId.trim() || 'YOUR_PROJECT_ID'}
                      </span>
                    </div>
                  </div>
                  <p className="text-xs text-slate-400 mt-2">
                    💡 Fill in your <strong>GCP Project ID</strong> below and the command updates automatically.
                  </p>
                </div>

                <Separator />

                {/* Step 2: BigQuery Billing Export */}
                <div ref={step2Ref}>
                  <div className="flex items-center gap-2 mb-3">
                    <div className="w-6 h-6 rounded-full bg-green-500 text-white text-xs font-bold flex items-center justify-center">2</div>
                    <h3 className="text-sm font-bold text-slate-900">Enable BigQuery Billing Export</h3>
                  </div>
                  <ol className="space-y-1.5 text-xs text-slate-600 leading-relaxed">
                    <li className="flex gap-2"><span className="text-green-500 font-bold flex-shrink-0">→</span> Go to <a href="https://console.cloud.google.com/billing" target="_blank" rel="noopener noreferrer" className="text-blue-600 font-semibold underline underline-offset-2 inline-flex items-center gap-0.5">GCP Console → Billing <ExternalLink className="w-3 h-3" /></a></li>
                    <li className="flex gap-2"><span className="text-green-500 font-bold flex-shrink-0">→</span> Navigate to <strong>Billing export</strong> → <strong>BigQuery export</strong></li>
                    <li className="flex gap-2"><span className="text-green-500 font-bold flex-shrink-0">→</span> Edit "Standard usage cost", select your project, create or select dataset <code className="bg-slate-100 px-1 rounded">billing_export</code>, and Save.</li>
                    <li className="flex gap-2"><span className="text-green-500 font-bold flex-shrink-0">→</span> Note the full table name — you'll need it below.</li>
                  </ol>
                </div>

                <Separator />

                {/* Step 3: Grant Access */}
                <div ref={step3Ref}>
                  <div className="flex items-center gap-2 mb-3">
                    <div className="w-6 h-6 rounded-full bg-yellow-500 text-white text-xs font-bold flex items-center justify-center">3</div>
                    <h3 className="text-sm font-bold text-slate-900">Grant BigQuery Access</h3>
                  </div>
                  <p className="text-xs text-slate-600 leading-relaxed">
                    In BigQuery Console, share the <code className="bg-slate-100 px-1 rounded">billing_export</code> dataset with the new service account (<code className="bg-slate-100 px-1 rounded">xamops@…</code>), granting it the <strong>BigQuery Data Viewer</strong> role.
                  </p>
                </div>
              </CardContent>
            </Card>
          </motion.div>
        )}
      </AnimatePresence>

      {/* Credentials Form Card */}
      <Card ref={step4Ref} className="shadow-sm border-slate-200 overflow-hidden">
        <div className="h-1 bg-gradient-to-r from-green-500 to-blue-600" />
        <CardContent className="p-6">
          <div className="flex items-start gap-4 mb-6">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-green-500 to-blue-600 text-white flex items-center justify-center flex-shrink-0 shadow-lg shadow-green-500/25 text-sm font-bold">4</div>
            <div>
              <h2 className="text-lg font-bold text-slate-900 tracking-tight">Enter Account Details</h2>
              <p className="text-sm text-slate-500">Paste the credentials generated from Steps 1–3 above</p>
            </div>
          </div>

          <form onSubmit={handleSubmit} className="space-y-5">
            {/* Account Name */}
            <div className="space-y-2">
              <Label htmlFor="gcpAccountName" className="flex items-center gap-1.5">
                <Tag className="w-3.5 h-3.5 text-blue-500" />
                Account Name
              </Label>
              <Input
                id="gcpAccountName"
                placeholder="e.g. Production GCP"
                value={accountName}
                onChange={(e) => setAccountName(e.target.value)}
                required
              />
            </div>

            {/* Project ID */}
            <div className="space-y-2">
              <Label htmlFor="gcpProjectId" className="flex items-center gap-1.5">
                <FolderOpen className="w-3.5 h-3.5 text-blue-500" />
                GCP Project ID
              </Label>
              <Input
                id="gcpProjectId"
                placeholder="your-gcp-project-id"
                value={projectId}
                onChange={(e) => setProjectId(e.target.value)}
                required
              />
              <p className="text-xs text-slate-400">Filling this in updates the Cloud Shell command above automatically</p>
            </div>

            {/* Service Account Key */}
            <div className="space-y-2">
              <Label htmlFor="gcpServiceAccountKey" className="flex items-center gap-1.5">
                <Key className="w-3.5 h-3.5 text-blue-500" />
                Service Account Key (JSON)
              </Label>
              <Textarea
                id="gcpServiceAccountKey"
                placeholder={`{\n  "type": "service_account",\n  "project_id": "your-project-id",\n  "private_key": "...",\n  "client_email": "xamops@...\n  ...\n}`}
                value={serviceAccountKey}
                onChange={(e) => setServiceAccountKey(e.target.value)}
                rows={7}
                required
              />
              <p className="text-xs text-slate-400">Paste the complete JSON output from the Cloud Shell script</p>
            </div>

            {/* BigQuery Billing Table */}
            <div className="space-y-2">
              <Label htmlFor="gcpBillingTable" className="flex items-center gap-1.5">
                <Database className="w-3.5 h-3.5 text-blue-500" />
                BigQuery Billing Export Table
                <span className="text-xs font-normal text-slate-400 ml-1">(Optional)</span>
              </Label>
              <Input
                id="gcpBillingTable"
                placeholder="project_id.billing_export.gcp_billing_export_v1_XXXXXX"
                value={billingTable}
                onChange={(e) => setBillingTable(e.target.value)}
                className="font-mono text-xs"
              />
              <p className="text-xs text-slate-400">
                Format: <code className="bg-slate-100 px-1 rounded">project.dataset.table</code> — May take up to 24h to be created. You can add this later from Account Manager.
              </p>
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
              <Button type="submit" variant="gcp" disabled={isLoading} className="gap-2">
                {isLoading ? (
                  <><Loader2 className="w-4 h-4 animate-spin" />Adding Account...</>
                ) : (
                  <><CheckCircle2 className="w-4 h-4" />Add GCP Account</>
                )}
              </Button>
            </div>
          </form>
        </CardContent>
      </Card>
    </motion.div>
  );
};

export default GCPForm;
