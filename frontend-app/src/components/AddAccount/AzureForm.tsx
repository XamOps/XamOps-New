import React, { useState } from 'react';
import { motion } from 'framer-motion';
import { Tag, Key, CheckCircle2, Loader2, Copy, Check, ExternalLink, Terminal } from 'lucide-react';
import { Button } from '../ui/button';
import { Input } from '../ui/input';
import { Label } from '../ui/label';
import { Textarea } from '../ui/textarea';
import { Card, CardContent } from '../ui/card';
import { Separator } from '../ui/separator';
import type { AzureDetails } from '../../types/addAccount';

interface AzureFormProps {
  azureDetails: AzureDetails;
  isLoading: boolean;
  onAzureDetailsChange: (details: Partial<AzureDetails>) => void;
  onSubmit: () => void;
  onCopyToClipboard: (text: string) => Promise<boolean>;
}

interface CopyState {
  [key: string]: boolean;
}

const commands = [
  { id: 'connect', label: 'Step 1 — Authenticate', code: 'Connect-AzAccount -UseDeviceAuthentication' },
  { id: 'context', label: 'Step 2 — Set Subscription', code: 'Set-AzContext -Subscription "YOUR_SUBSCRIPTION_ID"', highlight: '"YOUR_SUBSCRIPTION_ID"' },
  { id: 'download', label: 'Step 3 — Download Script', code: 'Invoke-WebRequest -Uri "https://xamops-azure-connector1.s3.ap-south-1.amazonaws.com/setup-xamops-connection1.ps" -OutFile "setup-xamops-connection.ps1"' },
  { id: 'source', label: 'Step 4 — Source Script', code: '. .\\setup-xamops-connection.ps1' },
  { id: 'setup', label: 'Step 5 — Run Setup', code: 'Setup-XamOpsConnection -SubscriptionId "YOUR_SUBSCRIPTION_ID" -ApplicationName "XamOps" -CreateSpCredential $true', highlight: '"YOUR_SUBSCRIPTION_ID"' },
];

const AzureForm: React.FC<AzureFormProps> = ({
  azureDetails, isLoading, onAzureDetailsChange, onSubmit, onCopyToClipboard,
}) => {
  const [copiedStates, setCopiedStates] = useState<CopyState>({});

  const handleCopy = async (text: string, id: string) => {
    const success = await onCopyToClipboard(text);
    if (success) {
      setCopiedStates((prev) => ({ ...prev, [id]: true }));
      setTimeout(() => setCopiedStates((prev) => ({ ...prev, [id]: false })), 2000);
    }
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    onSubmit();
  };

  return (
    <motion.div initial={{ opacity: 0, y: 16 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.4 }} className="space-y-4">

      {/* Script Instructions Card */}
      <Card className="shadow-sm border-slate-200 overflow-hidden">
        <div className="h-1 bg-gradient-to-r from-sky-500 to-blue-600" />
        <CardContent className="p-6">
          <div className="flex items-start gap-4 mb-6">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-sky-500 to-blue-600 text-white flex items-center justify-center flex-shrink-0 shadow-lg shadow-sky-500/25 text-sm font-bold">2</div>
            <div>
              <h2 className="text-lg font-bold text-slate-900 tracking-tight">Run Azure Setup Script</h2>
              <p className="text-sm text-slate-500">Execute these commands in Azure Cloud Shell to create a Service Principal</p>
            </div>
          </div>

          {/* Info notice */}
          <div className="flex items-center gap-3 p-3.5 bg-sky-50 border border-sky-200 rounded-xl mb-5">
            <Terminal className="w-4 h-4 text-sky-600 flex-shrink-0" />
            <p className="text-sm text-sky-800">
              Open{' '}
              <a href="https://shell.azure.com/" target="_blank" rel="noopener noreferrer" className="font-semibold underline underline-offset-2 hover:text-sky-900 inline-flex items-center gap-0.5">
                Azure Cloud Shell <ExternalLink className="w-3 h-3" />
              </a>
              {' '}and select <strong>PowerShell</strong>. Run each command in sequence.
            </p>
          </div>

          {/* Commands */}
          <div className="space-y-3">
            {commands.map((cmd) => {
              const isCopied = copiedStates[cmd.id];
              return (
                <div key={cmd.id} className="rounded-xl overflow-hidden border border-slate-800/10">
                  {/* Command label bar */}
                  <div className="flex items-center justify-between px-4 py-2 bg-slate-800">
                    <div className="flex items-center gap-2">
                      <div className="flex gap-1.5">
                        <span className="w-2.5 h-2.5 rounded-full bg-red-500/70" />
                        <span className="w-2.5 h-2.5 rounded-full bg-yellow-500/70" />
                        <span className="w-2.5 h-2.5 rounded-full bg-green-500/70" />
                      </div>
                      <span className="text-xs text-slate-400 font-medium ml-1">{cmd.label}</span>
                    </div>
                    <button
                      type="button"
                      onClick={() => handleCopy(cmd.code, cmd.id)}
                      className={`flex items-center gap-1.5 text-xs font-medium px-2.5 py-1 rounded-md transition-all duration-200 ${
                        isCopied
                          ? 'bg-emerald-500/20 text-emerald-400 border border-emerald-500/30'
                          : 'bg-white/8 text-slate-300 border border-white/10 hover:bg-white/15 hover:text-white'
                      }`}
                    >
                      {isCopied ? <><Check className="w-3 h-3" />Copied!</> : <><Copy className="w-3 h-3" />Copy</>}
                    </button>
                  </div>
                  {/* Code */}
                  <div className="px-4 py-3.5 bg-slate-900 font-mono text-xs text-slate-300 leading-relaxed overflow-x-auto">
                    {cmd.highlight ? (
                      <>
                        {cmd.code.split(cmd.highlight)[0]}
                        <span className="text-amber-300 font-medium">{cmd.highlight}</span>
                        {cmd.code.split(cmd.highlight)[1]}
                      </>
                    ) : cmd.code}
                  </div>
                </div>
              );
            })}
          </div>
        </CardContent>
      </Card>

      {/* Credentials Card */}
      <Card className="shadow-sm border-slate-200 overflow-hidden">
        <div className="h-1 bg-gradient-to-r from-blue-600 to-indigo-600" />
        <CardContent className="p-6">
          <div className="flex items-start gap-4 mb-6">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-blue-600 to-indigo-600 text-white flex items-center justify-center flex-shrink-0 shadow-lg shadow-blue-600/25 text-sm font-bold">3</div>
            <div>
              <h2 className="text-lg font-bold text-slate-900 tracking-tight">Enter Credentials</h2>
              <p className="text-sm text-slate-500">Paste the JSON output from the setup script</p>
            </div>
          </div>

          <form onSubmit={handleSubmit} className="space-y-5">
            <div className="space-y-2">
              <Label htmlFor="azureAccountName" className="flex items-center gap-1.5">
                <Tag className="w-3.5 h-3.5 text-sky-500" />
                Account Name
              </Label>
              <Input
                id="azureAccountName"
                placeholder="e.g., Azure Production Subscription"
                value={azureDetails.accountName}
                onChange={(e) => onAzureDetailsChange({ accountName: e.target.value })}
                required
              />
              <p className="text-xs text-slate-400">A friendly name for this Azure subscription</p>
            </div>

            <div className="space-y-2">
              <Label htmlFor="azureCredentialsJson" className="flex items-center gap-1.5">
                <Key className="w-3.5 h-3.5 text-sky-500" />
                Azure Credentials (JSON)
              </Label>
              <Textarea
                id="azureCredentialsJson"
                placeholder={'{\n  "appId": "...",\n  "password": "...",\n  "tenant": "..."\n}'}
                value={azureDetails.azureCredentialsJson}
                onChange={(e) => onAzureDetailsChange({ azureCredentialsJson: e.target.value })}
                rows={8}
                required
              />
              <p className="text-xs text-slate-400">The JSON credentials output from the PowerShell script</p>
            </div>

            <Separator />

            <div className="flex justify-end">
              <Button type="submit" variant="azure" disabled={isLoading} className="gap-2">
                {isLoading ? (
                  <><Loader2 className="w-4 h-4 animate-spin" />Connecting...</>
                ) : (
                  <><CheckCircle2 className="w-4 h-4" />Verify &amp; Connect</>
                )}
              </Button>
            </div>
          </form>
        </CardContent>
      </Card>
    </motion.div>
  );
};

export default AzureForm;
