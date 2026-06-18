import React from 'react';
import { GitHubRun, JenkinsJob, CicdDailyStat } from '../../types/cicdPipelines';

interface Props {
  githubRuns: GitHubRun[];
  jenkinsJobs: JenkinsJob[];
  githubDailyStats: CicdDailyStat[];
  jenkinsDailyStats: CicdDailyStat[];
  onCardClick: (provider: 'github' | 'jenkins', name: string) => void;
}

function timeAgo(dateStr: string | undefined): string {
  if (!dateStr) return '—';
  const diff = Date.now() - new Date(dateStr).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  return `${Math.floor(hrs / 24)}d ago`;
}

function statusColor(status: string): string {
  const s = (status || '').toLowerCase();
  if (s === 'success') return 'bg-green-100 text-green-700 border-green-200';
  if (s === 'failure' || s === 'failed') return 'bg-red-100 text-red-700 border-red-200';
  if (s === 'running' || s === 'in_progress') return 'bg-blue-100 text-blue-700 border-blue-200';
  return 'bg-gray-100 text-gray-500 border-gray-200';
}

const Sparkline: React.FC<{ data: number[] }> = ({ data }) => (
  <div className="flex items-end gap-[2px] h-6">
    {data.map((v, i) => (
      <div
        key={i}
        className={`w-2 rounded-sm ${v > 0 ? 'bg-green-400' : 'bg-red-400'}`}
        style={{ height: `${Math.max(30, v > 0 ? 100 : 40)}%` }}
      />
    ))}
  </div>
);

const CicdHealthScorecard: React.FC<Props> = ({
  githubRuns, jenkinsJobs, githubDailyStats, jenkinsDailyStats, onCardClick,
}) => {
  const sparklineData = (stats: CicdDailyStat[]) =>
    stats.slice(-7).map(s => s.successCount > 0 ? 1 : 0);

  const githubSuccessRate = githubRuns.length === 0 ? 0 :
    Math.round((githubRuns.filter(r => r.displayStatus === 'success').length / githubRuns.length) * 100);

  const jenkinsSuccessRate = jenkinsJobs.length === 0 ? 0 :
    Math.round((jenkinsJobs.filter(j => j.displayStatus === 'Success').length / jenkinsJobs.length) * 100);

  if (githubRuns.length === 0 && jenkinsJobs.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-16 text-[rgba(0,0,0,0.4)]">
        <p className="text-[14px]">Connect a GitHub repo or Jenkins server to see health scores</p>
      </div>
    );
  }

  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4 p-4">
      {githubRuns.length > 0 && (
        <div
          className="border border-[rgba(0,0,0,0.08)] rounded-lg p-4 cursor-pointer hover:border-[rgba(0,0,0,0.2)] transition-all bg-white"
          onClick={() => onCardClick('github', 'GitHub Actions')}
        >
          <div className="flex items-center justify-between mb-2">
            <span className="text-[13px] font-semibold text-[rgba(0,0,0,0.75)]">GitHub Actions</span>
            <span className={`text-[11px] px-2 py-0.5 rounded border font-medium ${statusColor(githubRuns[0]?.displayStatus)}`}>
              {(githubRuns[0]?.displayStatus || 'Unknown').toUpperCase()}
            </span>
          </div>
          <div className="text-[24px] font-bold text-[rgba(0,0,0,0.85)] mb-1">{githubSuccessRate}%</div>
          <div className="text-[11px] text-[rgba(0,0,0,0.4)] mb-3">success rate · {timeAgo(githubRuns[0]?.updated_at)}</div>
          <Sparkline data={sparklineData(githubDailyStats)} />
        </div>
      )}
      {jenkinsJobs.map(job => (
        <div
          key={job.name}
          className="border border-[rgba(0,0,0,0.08)] rounded-lg p-4 cursor-pointer hover:border-[rgba(0,0,0,0.2)] transition-all bg-white"
          onClick={() => onCardClick('jenkins', job.name)}
        >
          <div className="flex items-center justify-between mb-2">
            <span className="text-[13px] font-semibold text-[rgba(0,0,0,0.75)] truncate max-w-[160px]">{job.name}</span>
            <span className={`text-[11px] px-2 py-0.5 rounded border font-medium ${statusColor(job.displayStatus)}`}>
              {job.displayStatus.toUpperCase()}
            </span>
          </div>
          <div className="text-[24px] font-bold text-[rgba(0,0,0,0.85)] mb-1">{jenkinsSuccessRate}%</div>
          <div className="text-[11px] text-[rgba(0,0,0,0.4)] mb-3">
            success rate · {job.lastBuild ? timeAgo(new Date(job.lastBuild.timestamp).toISOString()) : '—'}
          </div>
          <Sparkline data={sparklineData(jenkinsDailyStats)} />
        </div>
      ))}
    </div>
  );
};

export default CicdHealthScorecard;
