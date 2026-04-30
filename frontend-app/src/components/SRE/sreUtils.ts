// frontend-app/src/components/SRE/sreUtils.ts
import { InvestigationStatus, ResourceType } from '../../hooks/useSreInvestigation';

export function timeAgo(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  const days = Math.floor(hrs / 24);
  return `${days}d ago`;
}

export function formatMttr(seconds: number | null): string {
  if (seconds === null) return '—';
  if (seconds < 60) return `${seconds}s`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  return `${h}h ${m}m`;
}

export function duration(start?: string, end?: string): string {
  if (!start || !end) return '';
  const secs = Math.floor((new Date(end).getTime() - new Date(start).getTime()) / 1000);
  return formatMttr(secs);
}

export const resourceTypeLabel: Record<ResourceType, string> = {
  K8S_POD: 'K8s Pod',
  EC2: 'EC2',
  RDS: 'RDS',
  AZURE_VM: 'Azure VM',
  GCP_VM: 'GCP VM',
  GENERIC: 'Service',
};

export const statusColors: Record<InvestigationStatus, { bg: string; text: string; dot: string }> = {
  PENDING:   { bg: 'bg-amber-50',   text: 'text-amber-700',  dot: 'bg-amber-400' },
  RUNNING:   { bg: 'bg-amber-50',   text: 'text-amber-700',  dot: 'bg-amber-400' },
  COMPLETED: { bg: 'bg-emerald-50', text: 'text-emerald-700',dot: 'bg-emerald-500' },
  FAILED:    { bg: 'bg-red-50',     text: 'text-red-600',    dot: 'bg-red-500' },
};
