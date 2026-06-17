import React from 'react';
import { motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import { useTheme } from '../../contexts/ThemeContext';

// ─── Types ────────────────────────────────────────────────────────────────────

export type BatchJobStatus = 'queued' | 'processing' | 'completed' | 'failed' | 'cancelled';

export interface BatchJobItem {
  id: number;
  name: string;
  status: BatchJobStatus;
  progress: number;
}

export interface BatchProgressProps {
  batchId: number;
  batchName: string;
  totalJobs: number;
  completedJobs: number;
  failedJobs: number;
  aggregateProgress: number;
  jobs: BatchJobItem[];
}

// ─── Reusable sub-components ─────────────────────────────────────────────────

function StatusBadge({ status }: { status: BatchJobStatus }) {
  const map: Record<string, { label: string; cls: string; dot: string }> = {
    processing: { label: 'Processing', cls: 'bg-blue-50 text-blue-700 border-blue-200', dot: 'bg-blue-500 animate-pulse' },
    queued: { label: 'Queued', cls: 'bg-amber-50 text-amber-700 border-amber-200', dot: 'bg-amber-400' },
    completed: { label: 'Completed', cls: 'bg-emerald-50 text-emerald-700 border-emerald-200', dot: 'bg-emerald-500' },
    failed: { label: 'Failed', cls: 'bg-red-50 text-red-700 border-red-200', dot: 'bg-red-500' },
    cancelled: { label: 'Cancelled', cls: 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.45)] border-[rgba(0,0,0,0.08)]', dot: 'bg-[rgba(0,0,0,0.2)]' },
  };
  const s = map[status] || map.queued;
  return (
    <span className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-[11px] font-semibold border ${s.cls}`}>
      <span className={`w-1.5 h-1.5 rounded-full ${s.dot}`} />
      {s.label}
    </span>
  );
}

function ProgressBar({ value, color = 'bg-pink-500', dark }: { value: number; color?: string; dark: boolean }) {
  return (
    <div className={`w-full h-1.5 rounded-full overflow-hidden ${dark ? 'bg-[rgba(255,255,255,0.06)]' : 'bg-[rgba(0,0,0,0.06)]'}`}>
      <div className={`h-full rounded-full transition-all duration-700 ${color}`} style={{ width: `${Math.min(100, Math.max(0, value))}%` }} />
    </div>
  );
}

// ─── Component ────────────────────────────────────────────────────────────────

export function BatchProgress({ batchId, batchName, totalJobs, completedJobs, failedJobs, aggregateProgress, jobs }: BatchProgressProps) {
  const { theme } = useTheme();
  const dark = theme === 'dark';

  const inProgressCount = jobs.filter(j => j.status === 'processing').length;
  const queuedCount = jobs.filter(j => j.status === 'queued').length;

  return (
    <div className={`border rounded-2xl shadow-sm overflow-hidden ${dark ? 'bg-[#1e1e1e] border-[rgba(255,255,255,0.08)]' : 'bg-white border-[rgba(0,0,0,0.08)]'}`}>
      {/* Header */}
      <div className={`px-5 py-4 border-b ${dark ? 'border-[rgba(255,255,255,0.06)]' : 'border-[rgba(0,0,0,0.06)]'}`}>
        <div className="flex items-center justify-between mb-3">
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 bg-pink-50 rounded-lg flex items-center justify-center">
              <Icon icon="mdi:playlist-play" className="text-pink-500 text-base" />
            </div>
            <div>
              <p className={`text-[13px] font-bold ${dark ? 'text-[rgba(255,255,255,0.88)]' : 'text-[rgba(0,0,0,0.88)]'}`}>{batchName}</p>
              <p className={`text-[10px] ${dark ? 'text-[rgba(255,255,255,0.4)]' : 'text-[rgba(0,0,0,0.4)]'}`}>Batch #{batchId}</p>
            </div>
          </div>
          <span className={`text-[11px] font-semibold ${dark ? 'text-[rgba(255,255,255,0.5)]' : 'text-[rgba(0,0,0,0.5)]'}`}>{aggregateProgress}% complete</span>
        </div>

        <motion.div initial={{ scaleX: 0 }} animate={{ scaleX: 1 }} transition={{ duration: 0.4, ease: 'easeOut' }} style={{ transformOrigin: 'left' }}>
          <div className={`w-full h-2.5 rounded-full overflow-hidden ${dark ? 'bg-[rgba(255,255,255,0.06)]' : 'bg-[rgba(0,0,0,0.06)]'}`}>
            <motion.div className="h-full rounded-full bg-gradient-to-r from-pink-500 to-rose-500"
              initial={{ width: 0 }} animate={{ width: `${aggregateProgress}%` }} transition={{ duration: 0.7, ease: 'easeOut' }} />
          </div>
        </motion.div>

        <div className="flex items-center gap-4 mt-3">
          <div className="flex items-center gap-1.5">
            <Icon icon="mdi:check-circle-outline" className="text-emerald-500 text-sm" />
            <span className={`text-[11px] ${dark ? 'text-[rgba(255,255,255,0.6)]' : 'text-[rgba(0,0,0,0.6)]'}`}><span className="font-semibold">{completedJobs}</span>/{totalJobs} completed</span>
          </div>
          {failedJobs > 0 && <div className="flex items-center gap-1.5"><Icon icon="mdi:alert-circle-outline" className="text-red-500 text-sm" /><span className="text-[11px] text-red-600 font-semibold">{failedJobs} failed</span></div>}
          {inProgressCount > 0 && <div className="flex items-center gap-1.5"><Icon icon="mdi:progress-clock" className="text-blue-500 text-sm" /><span className={`text-[11px] ${dark ? 'text-[rgba(255,255,255,0.6)]' : 'text-[rgba(0,0,0,0.6)]'}`}>{inProgressCount} processing</span></div>}
          {queuedCount > 0 && <div className="flex items-center gap-1.5"><Icon icon="mdi:clock-outline" className="text-amber-500 text-sm" /><span className={`text-[11px] ${dark ? 'text-[rgba(255,255,255,0.6)]' : 'text-[rgba(0,0,0,0.6)]'}`}>{queuedCount} queued</span></div>}
        </div>
      </div>

      {/* Per-job list */}
      <div className={`max-h-72 overflow-y-auto ${dark ? 'divide-y divide-[rgba(255,255,255,0.04)]' : 'divide-y divide-[rgba(0,0,0,0.04)]'}`}>
        {jobs.map(job => (
          <div key={job.id} className={`flex items-center gap-3 px-5 py-3 transition-colors ${dark ? 'hover:bg-[rgba(255,255,255,0.01)]' : 'hover:bg-[rgba(0,0,0,0.01)]'}`}>
            <div className={`w-7 h-7 rounded-lg flex items-center justify-center flex-shrink-0 ${
              job.status === 'completed' ? 'bg-emerald-50' : job.status === 'processing' ? 'bg-blue-50' : job.status === 'failed' ? 'bg-red-50' : 'bg-amber-50'
            }`}>
              <Icon icon={job.status === 'completed' ? 'mdi:check' : job.status === 'processing' ? 'mdi:loading' : job.status === 'failed' ? 'mdi:close' : 'mdi:clock-outline'}
                className={`text-xs ${job.status === 'completed' ? 'text-emerald-500' : job.status === 'processing' ? 'text-blue-500 animate-spin' : job.status === 'failed' ? 'text-red-500' : 'text-amber-500'}`} />
            </div>
            <div className="flex-1 min-w-0">
              <p className={`text-[12px] font-semibold truncate ${dark ? 'text-[rgba(255,255,255,0.8)]' : 'text-[rgba(0,0,0,0.8)]'}`}>{job.name}</p>
              {job.status === 'processing' && <div className="mt-1"><ProgressBar value={job.progress} color="bg-blue-500" dark={dark} /></div>}
            </div>
            <div className="text-right flex-shrink-0 flex items-center gap-2">
              <StatusBadge status={job.status} />
              {job.status === 'processing' && <span className={`text-[10px] font-semibold ${dark ? 'text-[rgba(255,255,255,0.4)]' : 'text-[rgba(0,0,0,0.4)]'}`}>{job.progress}%</span>}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
