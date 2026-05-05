import React from 'react';
import { motion } from 'framer-motion';
import { Server, User, KeyRound, Loader2, Plug, Globe } from 'lucide-react';
import { Button } from '../ui/button';
import { Input } from '../ui/input';
import { Label } from '../ui/label';
import { Card, CardContent } from '../ui/card';
import { Separator } from '../ui/separator';
import type { JenkinsDetails } from '../../types/addAccount';

interface JenkinsFormProps {
  jenkinsDetails: JenkinsDetails;
  isLoading: boolean;
  onJenkinsDetailsChange: (details: Partial<JenkinsDetails>) => void;
  onSubmit: () => void;
}

const JenkinsForm: React.FC<JenkinsFormProps> = ({
  jenkinsDetails, isLoading, onJenkinsDetailsChange, onSubmit,
}) => {
  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    onSubmit();
  };

  return (
    <motion.div initial={{ opacity: 0, y: 16 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.4 }}>
      <Card className="shadow-sm border-slate-200 overflow-hidden">
        <div className="h-1 bg-gradient-to-r from-red-500 to-red-600" />
        <CardContent className="p-6">
          {/* Header */}
          <div className="flex items-start gap-4 mb-6">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-red-500 to-red-600 text-white flex items-center justify-center flex-shrink-0 shadow-lg shadow-red-500/25 text-sm font-bold">2</div>
            <div>
              <h2 className="text-lg font-bold text-slate-900 tracking-tight">Connect Jenkins Server</h2>
              <p className="text-sm text-slate-500">Enter your Jenkins server URL and API credentials</p>
            </div>
          </div>

          {/* Info banner */}
          <div className="flex items-center gap-3 p-3.5 bg-slate-50 border border-slate-200 rounded-xl mb-6">
            <Server className="w-4 h-4 text-slate-500 flex-shrink-0" />
            <p className="text-xs text-slate-600">
              You'll need a Jenkins API token. Generate one at <strong>User Profile → Configure → API Token</strong>
            </p>
          </div>

          <form onSubmit={handleSubmit} className="space-y-5">
            <div className="space-y-2">
              <Label htmlFor="jenkinsUrl" className="flex items-center gap-1.5">
                <Globe className="w-3.5 h-3.5 text-red-500" />
                Jenkins URL
              </Label>
              <Input
                type="url"
                id="jenkinsUrl"
                placeholder="https://jenkins.yourcompany.com"
                value={jenkinsDetails.url}
                onChange={(e) => onJenkinsDetailsChange({ url: e.target.value })}
                required
              />
              <p className="text-xs text-slate-400">Full URL including protocol (http/https) and port if applicable</p>
            </div>

            <div className="space-y-2">
              <Label htmlFor="jenkinsUsername" className="flex items-center gap-1.5">
                <User className="w-3.5 h-3.5 text-red-500" />
                Username
              </Label>
              <Input
                id="jenkinsUsername"
                placeholder="admin"
                value={jenkinsDetails.username}
                onChange={(e) => onJenkinsDetailsChange({ username: e.target.value })}
                required
              />
              <p className="text-xs text-slate-400">Your Jenkins username</p>
            </div>

            <div className="space-y-2">
              <Label htmlFor="jenkinsToken" className="flex items-center gap-1.5">
                <KeyRound className="w-3.5 h-3.5 text-red-500" />
                API Token
              </Label>
              <Input
                type="password"
                id="jenkinsToken"
                placeholder="Enter your User API Token"
                value={jenkinsDetails.token}
                onChange={(e) => onJenkinsDetailsChange({ token: e.target.value })}
                required
              />
              <p className="text-xs text-slate-400">Generate at: User Profile → Configure → API Token</p>
            </div>

            <Separator />

            <div className="flex justify-end">
              <Button type="submit" variant="jenkins" disabled={isLoading} className="gap-2">
                {isLoading ? (
                  <><Loader2 className="w-4 h-4 animate-spin" />Connecting...</>
                ) : (
                  <><Plug className="w-4 h-4" />Connect Jenkins</>
                )}
              </Button>
            </div>
          </form>
        </CardContent>
      </Card>
    </motion.div>
  );
};

export default JenkinsForm;
