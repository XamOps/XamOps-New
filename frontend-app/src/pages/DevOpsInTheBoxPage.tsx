import React, { useState, useCallback, useEffect } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import { useDevOpsScripts } from '../hooks/useDevOpsScripts';
import { DevOpsScript, ScriptCategory } from '../types/devopsInTheBox';
import { useTopbar } from '../contexts/TopbarContext';
import { DialogRoot, DialogPanel } from '../components/ui';

// ─── Helpers ──────────────────────────────────────────────────────────────────

const CATEGORY_META: Record<string, { icon: string; iconColor: string; bg: string; text: string; border: string; activeBg: string }> = {
  terraform: { icon: 'simple-icons:terraform',  iconColor: '#7C3AED', bg: 'bg-purple-50',  text: 'text-purple-700', border: 'border-purple-200', activeBg: 'bg-purple-600' },
  bash:      { icon: 'mdi:console',             iconColor: '#16A34A', bg: 'bg-emerald-50', text: 'text-emerald-700', border: 'border-emerald-200', activeBg: 'bg-emerald-600' },
  python:    { icon: 'mdi:language-python',     iconColor: '#2563EB', bg: 'bg-blue-50',    text: 'text-blue-700',   border: 'border-blue-200',   activeBg: 'bg-blue-600' },
  ansible:   { icon: 'simple-icons:ansible',    iconColor: '#DC2626', bg: 'bg-red-50',     text: 'text-red-700',    border: 'border-red-200',    activeBg: 'bg-red-600' },
};

const DEFAULT_META = { icon: 'mdi:file-code-outline', iconColor: 'rgba(0,0,0,0.4)', bg: 'bg-[rgba(0,0,0,0.03)]', text: 'text-[rgba(0,0,0,0.55)]', border: 'border-[rgba(0,0,0,0.08)]', activeBg: 'bg-[rgba(0,0,0,0.5)]' };

function catMeta(cat: string) { return CATEGORY_META[cat?.toLowerCase()] || DEFAULT_META; }

function getFileName(script: DevOpsScript): string {
  const name = (script.name || 'script').replace(/\s+/g, '-').toLowerCase();
  switch (script.category?.toLowerCase()) {
    case 'python':    return `${name}.py`;
    case 'bash':      return `${name}.sh`;
    case 'terraform': return 'main.tf';
    default:          return `${name}.txt`;
  }
}

// ─── Page ─────────────────────────────────────────────────────────────────────

const DevOpsInTheBoxPage: React.FC = () => {
  const { filteredScripts, isLoading, error, filters, setSearchTerm, setFilterCategory, refreshScripts } = useDevOpsScripts();
  const [selectedScript, setSelectedScript] = useState<DevOpsScript | null>(null);
  const [copySuccess, setCopySuccess] = useState(false);

  const { setActions, clearActions } = useTopbar();
  useEffect(() => {
    setActions(
      <div className="flex items-center gap-2">
        <span className="w-2 h-2 rounded-full bg-amber-400" />
        <span className="text-[13px] font-semibold text-[rgba(0,0,0,0.65)]">DevOps in the Box</span>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions]);

  const copyCode = useCallback(() => {
    if (!selectedScript?.code) return;
    navigator.clipboard.writeText(selectedScript.code).then(() => {
      setCopySuccess(true);
      setTimeout(() => setCopySuccess(false), 2000);
    }).catch(() => alert('Failed to copy code to clipboard'));
  }, [selectedScript]);

  const downloadScript = useCallback(() => {
    if (!selectedScript?.code) return;
    const el = document.createElement('a');
    el.href = URL.createObjectURL(new Blob([selectedScript.code], { type: 'text/plain' }));
    el.download = getFileName(selectedScript);
    document.body.appendChild(el);
    el.click();
    document.body.removeChild(el);
  }, [selectedScript]);

  useEffect(() => {
    if (!selectedScript) return;
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') setSelectedScript(null); };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [selectedScript]);

  const CATEGORIES: Array<{ id: ScriptCategory | 'all'; label: string; icon: string }> = [
    { id: 'all',       label: 'All',       icon: 'mdi:view-grid-outline' },
    { id: 'terraform', label: 'Terraform', icon: 'simple-icons:terraform' },
    { id: 'bash',      label: 'Bash',      icon: 'mdi:console' },
    { id: 'python',    label: 'Python',    icon: 'mdi:language-python' },
    { id: 'ansible',   label: 'Ansible',   icon: 'simple-icons:ansible' },
  ];

  return (
    <main className="flex-1 px-6 pt-4 pb-6 space-y-5">

      {/* Search + Filters */}
      <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl shadow-sm p-5">
        <div className="flex flex-col md:flex-row gap-4">
          {/* Search */}
          <div className="relative flex-1">
            <Icon icon="mdi:magnify" width={15} color="rgba(0,0,0,0.3)" className="absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none" />
            <input
              type="text"
              value={filters.searchTerm}
              onChange={e => setSearchTerm(e.target.value)}
              placeholder="Search scripts by name or description…"
              className="w-full pl-9 pr-4 py-2.5 border border-[rgba(0,0,0,0.12)] rounded-lg text-[13px] text-[rgba(0,0,0,0.88)] focus:ring-2 focus:ring-indigo-400 focus:border-indigo-400 outline-none bg-white placeholder-[rgba(0,0,0,0.3)]"
            />
          </div>
          {/* Reset */}
          <button onClick={() => { setFilterCategory('all'); setSearchTerm(''); }}
            className="flex items-center gap-1.5 px-4 py-2.5 text-[12px] font-semibold text-[rgba(0,0,0,0.65)] bg-white border border-[rgba(0,0,0,0.12)] rounded-lg hover:bg-[rgba(0,0,0,0.02)] transition-colors">
            <Icon icon="mdi:filter-off-outline" width={13} />
            Reset Filters
          </button>
        </div>

        {/* Category pills */}
        <div className="flex flex-wrap gap-2 mt-4">
          {CATEGORIES.map(cat => {
            const active = filters.filterCategory === cat.id;
            const m = cat.id === 'all' ? null : catMeta(cat.id);
            return (
              <button
                key={cat.id}
                onClick={() => { setFilterCategory(cat.id); setSearchTerm(''); }}
                className={`flex items-center gap-1.5 px-3.5 py-1.5 rounded-full text-[12px] font-semibold border transition-all ${
                  active
                    ? (m ? `${m.activeBg} text-white border-transparent` : 'bg-indigo-600 text-white border-transparent')
                    : 'bg-white text-[rgba(0,0,0,0.55)] border-[rgba(0,0,0,0.10)] hover:bg-[rgba(0,0,0,0.03)]'
                }`}
              >
                <Icon icon={cat.icon} width={13} />
                {cat.label}
              </button>
            );
          })}
        </div>
      </div>

      {/* Error */}
      {error && (
        <div className="bg-white border border-red-200 rounded-xl p-4 shadow-sm flex items-start gap-3">
          <Icon icon="mdi:alert-circle-outline" width={16} color="#DC2626" className="mt-0.5 flex-shrink-0" />
          <div>
            <p className="font-bold text-red-800 text-[13px]">Error</p>
            <p className="text-[12px] text-red-700 mt-0.5">{error}</p>
          </div>
        </div>
      )}

      <AnimatePresence mode="wait">

      {/* Loading */}
      {isLoading && (
        <motion.div key="loading" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} transition={{ duration: 0.15 }} className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {[...Array(6)].map((_, i) => (
            <div key={i} className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl h-44 animate-pulse" />
          ))}
        </motion.div>
      )}

      {/* Scripts Grid */}
      {!isLoading && filteredScripts.length > 0 && (
        <motion.div key="grid" initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.28, ease: [0.22, 1, 0.36, 1] }} className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {filteredScripts.map((script, idx) => {
            const m = catMeta(script.category);
            return (
              <div key={script.id} onClick={() => setSelectedScript(script)}
                style={{ animation: `fadeInUp 0.3s ease ${Math.min(idx * 0.04, 0.4)}s both` }}
                className={`bg-white border ${m.border} rounded-xl p-5 cursor-pointer hover:shadow-md transition-all`}>
                <div className="flex items-start justify-between mb-3">
                  <div className={`w-10 h-10 ${m.bg} border ${m.border} rounded-lg flex items-center justify-center flex-shrink-0`}>
                    <Icon icon={m.icon} width={18} color={m.iconColor} />
                  </div>
                  <span className={`inline-flex items-center gap-1 px-2.5 py-1 ${m.bg} border ${m.border} rounded-full text-[11px] font-semibold ${m.text} capitalize`}>
                    {script.category}
                  </span>
                </div>
                <h3 className="font-bold text-[rgba(0,0,0,0.88)] text-[14px] mb-1.5 line-clamp-1">{script.name}</h3>
                <p className="text-[12px] text-[rgba(0,0,0,0.45)] line-clamp-2 mb-4 leading-relaxed">{script.description}</p>
                <div className="flex items-center justify-between pt-3 border-t border-[rgba(0,0,0,0.06)]">
                  <div className="flex items-center gap-3 text-[11px] text-[rgba(0,0,0,0.4)]">
                    <span className="flex items-center gap-1">
                      <Icon icon="mdi:download-outline" width={12} />
                      {script.downloads}
                    </span>
                    <span className="flex items-center gap-1">
                      <Icon icon="mdi:clock-outline" width={12} />
                      {script.lastUpdated}
                    </span>
                  </div>
                  <span className="flex items-center gap-1 text-[11px] font-semibold text-indigo-600">
                    View <Icon icon="mdi:arrow-right" width={12} />
                  </span>
                </div>
              </div>
            );
          })}
        </motion.div>
      )}

      {/* Empty */}
      {!isLoading && filteredScripts.length === 0 && !error && (
        <motion.div key="empty" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }}>
          <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl py-16 shadow-sm text-center">
            <div className="w-12 h-12 bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.06)] rounded-xl flex items-center justify-center mx-auto mb-3">
              <Icon icon="mdi:magnify" width={24} color="rgba(0,0,0,0.2)" />
            </div>
            <p className="text-[14px] font-bold text-[rgba(0,0,0,0.55)]">No Scripts Found</p>
            <p className="text-[12px] text-[rgba(0,0,0,0.35)] mt-1">
              {filters.searchTerm || filters.filterCategory !== 'all'
                ? 'Try adjusting your search or filters.'
                : 'No scripts available at the moment.'}
            </p>
          </div>
        </motion.div>
      )}

      </AnimatePresence>

      {/* Script Detail Modal */}
      <DialogRoot isOpen={!!selectedScript} onClose={() => setSelectedScript(null)} maxWidth="max-w-4xl">
        {selectedScript && (() => {
          const m = catMeta(selectedScript.category);
          return (
            <DialogPanel
              title={
                <div className="flex items-center gap-3 min-w-0">
                  <div className={`w-10 h-10 ${m.bg} border ${m.border} rounded-lg flex items-center justify-center flex-shrink-0`}>
                    <Icon icon={m.icon} width={18} color={m.iconColor} />
                  </div>
                  <div className="min-w-0">
                    <p className="text-[15px] font-bold text-[rgba(0,0,0,0.88)] truncate">{selectedScript.name}</p>
                    <p className="text-[12px] text-[rgba(0,0,0,0.45)] mt-0.5 truncate">{selectedScript.description}</p>
                  </div>
                </div>
              }
              onClose={() => setSelectedScript(null)}
              footer={
                <div className="flex items-center gap-3 w-full">
                  <button onClick={downloadScript}
                    className="flex items-center gap-2 px-5 py-2.5 text-[13px] font-bold text-white bg-emerald-600 hover:bg-emerald-700 rounded-xl transition-colors">
                    <Icon icon="mdi:download-outline" width={14} />
                    Download Script
                  </button>
                  <button onClick={() => setSelectedScript(null)}
                    className="ml-auto px-5 py-2.5 text-[13px] font-semibold text-[rgba(0,0,0,0.65)] bg-white border border-[rgba(0,0,0,0.12)] rounded-xl hover:bg-[rgba(0,0,0,0.02)] transition-colors">
                    Close
                  </button>
                </div>
              }
            >
              <div className="overflow-y-auto max-h-[60vh] space-y-5">
                {/* Metadata */}
                <div className="grid grid-cols-2 gap-3">
                  {[
                    { label: 'Category',     value: <span className="capitalize">{selectedScript.category}</span> },
                    { label: 'Downloads',    value: selectedScript.downloads },
                    { label: 'Last Updated', value: selectedScript.lastUpdated },
                    { label: 'Version',      value: selectedScript.version },
                  ].map(item => (
                    <div key={item.label} className="bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.06)] rounded-xl p-4">
                      <p className="text-[11px] font-semibold text-[rgba(0,0,0,0.4)] uppercase tracking-[0.07em] mb-1">{item.label}</p>
                      <p className="font-bold text-[rgba(0,0,0,0.88)] text-[13px]">{item.value}</p>
                    </div>
                  ))}
                </div>

                {/* Code Block */}
                <div className="rounded-xl overflow-hidden border border-[rgba(255,255,255,0.06)]" style={{ background: '#1a1d27' }}>
                  <div className="flex items-center justify-between px-5 py-3 border-b border-[rgba(255,255,255,0.07)]">
                    <span className="text-[12px] font-mono text-[rgba(255,255,255,0.5)]">{getFileName(selectedScript)}</span>
                    <button onClick={copyCode}
                      className="flex items-center gap-1.5 text-[11px] font-semibold text-[rgba(255,255,255,0.5)] hover:text-white transition-colors px-2.5 py-1 rounded-lg hover:bg-[rgba(255,255,255,0.07)]">
                      <Icon icon={copySuccess ? 'mdi:check' : 'mdi:content-copy'} width={13} color={copySuccess ? '#10B981' : undefined} />
                      {copySuccess ? 'Copied!' : 'Copy'}
                    </button>
                  </div>
                  <div className="p-5 overflow-x-auto">
                    <pre className="text-[13px] font-mono whitespace-pre-wrap break-words" style={{ color: '#4ade80' }}>
                      {selectedScript.code}
                    </pre>
                  </div>
                </div>

                {/* Usage */}
                <div className="bg-blue-50 border border-blue-100 rounded-xl p-5">
                  <h3 className="flex items-center gap-2 font-bold text-[rgba(0,0,0,0.88)] text-[13px] mb-3">
                    <Icon icon="mdi:information-outline" width={15} color="#2563EB" />
                    Usage Instructions
                  </h3>
                  <div className="text-[13px] text-[rgba(0,0,0,0.7)] prose prose-sm max-w-none"
                    dangerouslySetInnerHTML={{ __html: selectedScript.usage }} />
                </div>
              </div>
            </DialogPanel>
          );
        })()}
      </DialogRoot>
    </main>
  );
};

export default DevOpsInTheBoxPage;
