import { useMemo } from 'react';
import { Icon } from '@iconify/react';

// ─── Types ────────────────────────────────────────────────────────────────────

interface AnalyticsJob {
  status: string;
  createdAt: string;
  completedAt?: string;
  outputSize: string;
  fileSize: string;
}

interface JobAnalyticsProps {
  jobs: AnalyticsJob[];
}

interface AnalyticsData {
  totalJobs: number;
  completionRate: number;
  avgProcessingTime: string;
  totalStorageSaved: string;
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function parseSize(sizeStr: string): number {
  if (!sizeStr || sizeStr === '—') return 0;
  const match = sizeStr.match(/^([\d.]+)\s*(B|KB|MB|GB|TB)?$/i);
  if (!match) return 0;
  const value = parseFloat(match[1]);
  const unit = (match[2] || 'B').toUpperCase();
  const multipliers: Record<string, number> = { B: 1, KB: 1024, MB: 1024 ** 2, GB: 1024 ** 3, TB: 1024 ** 4 };
  return value * (multipliers[unit] || 1);
}

function formatBytes(bytes: number): string {
  if (!bytes || bytes <= 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(1))} ${sizes[i]}`;
}

function formatDuration(ms: number): string {
  if (!ms || ms <= 0) return '—';
  const seconds = Math.round(ms / 1000);
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  const remainSec = seconds % 60;
  if (minutes < 60) return `${minutes}m ${remainSec}s`;
  const hours = Math.floor(minutes / 60);
  const remainMin = minutes % 60;
  return `${hours}h ${remainMin}m`;
}

// ─── Analytics Calculation ────────────────────────────────────────────────────

export function calculateAnalytics(jobs: AnalyticsJob[]): AnalyticsData {
  const totalJobs = jobs.length;

  const completedJobs = jobs.filter((j) => j.status === 'completed');
  const completionRate = totalJobs > 0 ? Math.round((completedJobs.length / totalJobs) * 100) : 0;

  // Average processing time for completed jobs with both createdAt and completedAt
  let avgProcessingTime = '—';
  const jobsWithTimes = completedJobs.filter((j) => j.createdAt && j.completedAt);
  if (jobsWithTimes.length > 0) {
    const totalMs = jobsWithTimes.reduce((sum, j) => {
      const created = new Date(j.createdAt).getTime();
      const completed = new Date(j.completedAt!).getTime();
      return sum + Math.max(0, completed - created);
    }, 0);
    avgProcessingTime = formatDuration(totalMs / jobsWithTimes.length);
  }

  // Total storage saved = sum(fileSize - outputSize) for completed jobs
  let totalSavedBytes = 0;
  for (const job of completedJobs) {
    const inputBytes = parseSize(job.fileSize);
    const outputBytes = parseSize(job.outputSize);
    if (inputBytes > 0 && outputBytes > 0 && inputBytes > outputBytes) {
      totalSavedBytes += inputBytes - outputBytes;
    }
  }
  const totalStorageSaved = formatBytes(totalSavedBytes);

  return { totalJobs, completionRate, avgProcessingTime, totalStorageSaved };
}

// ─── Component ────────────────────────────────────────────────────────────────

export function JobAnalytics({ jobs }: JobAnalyticsProps) {
  const analytics = useMemo(() => calculateAnalytics(jobs), [jobs]);

  const stats = [
    {
      label: 'Total Jobs',
      value: analytics.totalJobs,
      icon: 'mdi:film-sync',
      iconBg: 'bg-blue-50',
      iconColor: 'text-blue-500',
    },
    {
      label: 'Completion Rate',
      value: `${analytics.completionRate}%`,
      icon: 'mdi:check-circle-outline',
      iconBg: 'bg-emerald-50',
      iconColor: 'text-emerald-500',
    },
    {
      label: 'Avg Processing',
      value: analytics.avgProcessingTime,
      icon: 'mdi:clock-outline',
      iconBg: 'bg-amber-50',
      iconColor: 'text-amber-500',
    },
    {
      label: 'Storage Saved',
      value: analytics.totalStorageSaved,
      icon: 'mdi:content-save-outline',
      iconBg: 'bg-pink-50',
      iconColor: 'text-pink-500',
    },
  ];

  return (
    <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
      {stats.map((stat) => (
        <div
          key={stat.label}
          className="flex items-center gap-3 px-3.5 py-2.5 bg-white border border-[rgba(0,0,0,0.08)] rounded-xl shadow-sm"
        >
          <div className={`w-8 h-8 rounded-lg ${stat.iconBg} flex items-center justify-center flex-shrink-0`}>
            <Icon icon={stat.icon} className={`${stat.iconColor} text-sm`} />
          </div>
          <div className="min-w-0">
            <p className="text-[10px] font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-wide">{stat.label}</p>
            <p className="text-[14px] font-bold text-[rgba(0,0,0,0.85)] truncate">{stat.value}</p>
          </div>
        </div>
      ))}
    </div>
  );
}
