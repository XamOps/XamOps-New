/**
 * EKS Resources Tab — Cast.ai / KubeCut level inventory
 *
 * Shows all K8s resource types from the k8s-resources collector:
 *   HPAs · PVCs · Services · Ingresses · K8s Events
 *
 * Data sourced from dashboard.k8sHpas, k8sPvcs, k8sServices,
 * k8sIngresses, k8sEvents pushed by xamops-agent.
 */

import React, { useState, useMemo } from 'react';

// ─── Generic badge helpers ────────────────────────────────────────────────────

const StatusBadge: React.FC<{ text: string; variant: 'green' | 'red' | 'amber' | 'blue' | 'gray' | 'purple' }> = ({ text, variant }) => {
  const map = {
    green: 'bg-green-100 text-green-700 border-green-200',
    red: 'bg-red-100 text-red-700 border-red-200',
    amber: 'bg-amber-100 text-amber-700 border-amber-200',
    blue: 'bg-blue-100 text-blue-700 border-blue-200',
    gray: 'bg-gray-100 text-gray-600 border-gray-200',
    purple: 'bg-purple-100 text-purple-700 border-purple-200',
  };
  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-bold border ${map[variant]}`}>
      {text}
    </span>
  );
};

const EmptyState: React.FC<{ icon: string; title: string; subtitle: string }> = ({ icon, title, subtitle }) => (
  <div className="flex flex-col items-center justify-center py-16 text-center">
    <div className="w-16 h-16 bg-gray-100 rounded-2xl flex items-center justify-center mb-4">
      <i className={`${icon} text-gray-300 text-2xl`}></i>
    </div>
    <p className="text-gray-600 font-semibold">{title}</p>
    <p className="text-gray-400 text-sm mt-1">{subtitle}</p>
  </div>
);

// ─── Section wrapper ──────────────────────────────────────────────────────────

const ResourceSection: React.FC<{
  icon: string;
  color: string;
  title: string;
  count: number;
  children: React.ReactNode;
}> = ({ icon, color, title, count, children }) => {
  const [open, setOpen] = useState(true);
  return (
    <section className="bg-white rounded-xl shadow-sm border border-gray-200 overflow-hidden">
      <button
        onClick={() => setOpen(o => !o)}
        className="w-full flex items-center justify-between px-6 py-4 hover:bg-gray-50 transition-colors"
      >
        <div className="flex items-center gap-3">
          <div className={`w-9 h-9 rounded-lg ${color} flex items-center justify-center`}>
            <i className={`fas ${icon} text-white text-sm`}></i>
          </div>
          <span className="font-bold text-gray-900 text-base">{title}</span>
          <span className="px-2.5 py-0.5 bg-gray-100 text-gray-600 rounded-full text-xs font-bold">{count}</span>
        </div>
        <i className={`fas fa-chevron-down text-gray-400 text-sm transition-transform ${open ? 'rotate-180' : ''}`}></i>
      </button>
      {open && <div className="border-t border-gray-100">{children}</div>}
    </section>
  );
};

// ─── Search + namespace filter bar ───────────────────────────────────────────

const FilterBar: React.FC<{
  search: string;
  onSearch: (v: string) => void;
  namespaces: string[];
  namespace: string;
  onNamespace: (v: string) => void;
  placeholder?: string;
}> = ({ search, onSearch, namespaces, namespace, onNamespace, placeholder = 'Search...' }) => (
  <div className="flex flex-col sm:flex-row gap-3 px-6 py-4 bg-gray-50 border-b border-gray-100">
    <div className="relative flex-1">
      <i className="fas fa-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-400 text-xs"></i>
      <input
        type="text"
        value={search}
        onChange={e => onSearch(e.target.value)}
        placeholder={placeholder}
        className="w-full pl-8 pr-4 py-2 bg-white border border-gray-200 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none"
      />
    </div>
    {namespaces.length > 0 && (
      <select
        value={namespace}
        onChange={e => onNamespace(e.target.value)}
        className="px-3 py-2 bg-white border border-gray-200 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 outline-none min-w-[160px]"
      >
        <option value="all">All Namespaces</option>
        {namespaces.map(ns => <option key={ns} value={ns}>{ns}</option>)}
      </select>
    )}
  </div>
);

// ─── HPA Section ─────────────────────────────────────────────────────────────

const HpaSection: React.FC<{ hpas: any[] }> = ({ hpas }) => {
  const [search, setSearch] = useState('');
  const [ns, setNs] = useState('all');

  const namespaces = useMemo(() => {
    const set = new Set<string>();
    hpas.forEach(h => h.namespace && set.add(h.namespace));
    return Array.from(set).sort();
  }, [hpas]);

  const filtered = useMemo(() => hpas.filter(h => {
    const matchNs = ns === 'all' || h.namespace === ns;
    const q = search.toLowerCase();
    const matchSearch = !q || (h.name || '').toLowerCase().includes(q) || (h.targetRef || '').toLowerCase().includes(q);
    return matchNs && matchSearch;
  }), [hpas, search, ns]);

  const atMax = filtered.filter(h => (h.currentReplicas || 0) >= (h.maxReplicas || 0)).length;

  return (
    <ResourceSection icon="fa-expand-arrows-alt" color="bg-purple-500" title="Horizontal Pod Autoscalers" count={hpas.length}>
      <FilterBar search={search} onSearch={setSearch} namespaces={namespaces} namespace={ns} onNamespace={setNs} placeholder="Search HPAs..." />
      {atMax > 0 && (
        <div className="mx-6 my-3 px-4 py-3 bg-amber-50 border border-amber-200 rounded-lg flex items-center gap-2 text-sm text-amber-800">
          <i className="fas fa-exclamation-triangle text-amber-500"></i>
          <strong>{atMax}</strong> HPA{atMax > 1 ? 's are' : ' is'} at max replicas — consider increasing <code>maxReplicas</code>
        </div>
      )}
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="bg-gray-50 border-b border-gray-100">
              <th className="text-left px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Name</th>
              <th className="text-left px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Namespace</th>
              <th className="text-left px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Target</th>
              <th className="text-center px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Min</th>
              <th className="text-center px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Current</th>
              <th className="text-center px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Max</th>
              <th className="text-center px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">CPU Target</th>
              <th className="text-center px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Status</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-50">
            {filtered.length === 0 ? (
              <tr><td colSpan={8} className="py-12 text-center text-gray-400 text-sm">No HPAs found</td></tr>
            ) : filtered.map((hpa: any, i: number) => {
              const current = hpa.currentReplicas ?? 0;
              const max = hpa.maxReplicas ?? 0;
              const min = hpa.minReplicas ?? 1;
              const isAtMax = current >= max && max > 0;
              const pct = max > 0 ? (current / max) * 100 : 0;
              return (
                <tr key={i} className="hover:bg-gray-50 transition-colors">
                  <td className="px-6 py-3 font-semibold text-gray-900">{hpa.name}</td>
                  <td className="px-6 py-3">
                    <span className="bg-gray-100 text-gray-600 px-2 py-0.5 rounded text-xs font-mono">{hpa.namespace}</span>
                  </td>
                  <td className="px-6 py-3 text-gray-600 text-xs font-mono">
                    {hpa.targetKind ? `${hpa.targetKind}/${hpa.targetRef}` : (hpa.targetRef || '—')}
                  </td>
                  <td className="px-6 py-3 text-center font-bold text-gray-700">{min}</td>
                  <td className="px-6 py-3 text-center">
                    <div className="flex flex-col items-center gap-1">
                      <span className={`font-black text-sm ${isAtMax ? 'text-amber-600' : 'text-gray-900'}`}>{current}</span>
                      <div className="w-12 h-1.5 bg-gray-100 rounded-full overflow-hidden">
                        <div className={`h-full rounded-full ${isAtMax ? 'bg-amber-500' : 'bg-purple-500'}`} style={{ width: `${pct}%` }} />
                      </div>
                    </div>
                  </td>
                  <td className="px-6 py-3 text-center font-bold text-gray-700">{max}</td>
                  <td className="px-6 py-3 text-center">
                    {hpa.targetCPUPercent
                      ? <span className="text-xs font-bold text-blue-600">{hpa.targetCPUPercent}%</span>
                      : <span className="text-gray-300">—</span>}
                  </td>
                  <td className="px-6 py-3 text-center">
                    {isAtMax
                      ? <StatusBadge text="At Max" variant="amber" />
                      : current === min
                        ? <StatusBadge text="At Min" variant="gray" />
                        : <StatusBadge text="Scaling" variant="green" />}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </ResourceSection>
  );
};

// ─── PVC Section ──────────────────────────────────────────────────────────────

const PvcSection: React.FC<{ pvcs: any[] }> = ({ pvcs }) => {
  const [search, setSearch] = useState('');
  const [ns, setNs] = useState('all');

  const namespaces = useMemo(() => {
    const set = new Set<string>();
    pvcs.forEach(p => p.namespace && set.add(p.namespace));
    return Array.from(set).sort();
  }, [pvcs]);

  const filtered = useMemo(() => pvcs.filter(p => {
    const matchNs = ns === 'all' || p.namespace === ns;
    const q = search.toLowerCase();
    return matchNs && (!q || (p.name || '').toLowerCase().includes(q) || (p.storageClass || '').toLowerCase().includes(q));
  }), [pvcs, search, ns]);

  const boundCount = filtered.filter(p => p.phase === 'Bound').length;
  const pendingCount = filtered.filter(p => p.phase !== 'Bound').length;

  const totalCapacityGi = useMemo(() => {
    return filtered.reduce((sum, p) => {
      const cap = p.capacity || '';
      const match = cap.match(/(\d+(?:\.\d+)?)\s*(Gi|Mi|Ti)/i);
      if (!match) return sum;
      const val = parseFloat(match[1]);
      const unit = match[2].toLowerCase();
      if (unit === 'gi') return sum + val;
      if (unit === 'mi') return sum + val / 1024;
      if (unit === 'ti') return sum + val * 1024;
      return sum;
    }, 0);
  }, [filtered]);

  return (
    <ResourceSection icon="fa-hdd" color="bg-teal-500" title="Persistent Volume Claims" count={pvcs.length}>
      <FilterBar search={search} onSearch={setSearch} namespaces={namespaces} namespace={ns} onNamespace={setNs} placeholder="Search PVCs..." />
      {/* Quick stats */}
      <div className="grid grid-cols-3 gap-4 px-6 py-4 bg-teal-50/50 border-b border-teal-100">
        <div className="text-center">
          <div className="text-xl font-black text-green-700">{boundCount}</div>
          <div className="text-xs text-gray-500 font-medium">Bound</div>
        </div>
        <div className="text-center border-x border-teal-100">
          <div className="text-xl font-black text-amber-600">{pendingCount}</div>
          <div className="text-xs text-gray-500 font-medium">Pending / Lost</div>
        </div>
        <div className="text-center">
          <div className="text-xl font-black text-teal-700">{totalCapacityGi.toFixed(0)} Gi</div>
          <div className="text-xs text-gray-500 font-medium">Total Capacity</div>
        </div>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="bg-gray-50 border-b border-gray-100">
              <th className="text-left px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Name</th>
              <th className="text-left px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Namespace</th>
              <th className="text-left px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Status</th>
              <th className="text-left px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Capacity</th>
              <th className="text-left px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Storage Class</th>
              <th className="text-left px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Access Mode</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-50">
            {filtered.length === 0 ? (
              <tr><td colSpan={6} className="py-12 text-center text-gray-400 text-sm">No PVCs found</td></tr>
            ) : filtered.map((pvc: any, i: number) => (
              <tr key={i} className="hover:bg-gray-50 transition-colors">
                <td className="px-6 py-3 font-semibold text-gray-900">{pvc.name}</td>
                <td className="px-6 py-3">
                  <span className="bg-gray-100 text-gray-600 px-2 py-0.5 rounded text-xs font-mono">{pvc.namespace}</span>
                </td>
                <td className="px-6 py-3">
                  {pvc.phase === 'Bound'
                    ? <StatusBadge text="Bound" variant="green" />
                    : pvc.phase === 'Pending'
                      ? <StatusBadge text="Pending" variant="amber" />
                      : <StatusBadge text={pvc.phase || 'Unknown'} variant="red" />}
                </td>
                <td className="px-6 py-3 font-mono text-sm text-teal-700 font-bold">{pvc.capacity || '—'}</td>
                <td className="px-6 py-3 text-xs text-gray-600 font-mono">{pvc.storageClass || 'default'}</td>
                <td className="px-6 py-3 text-xs text-gray-500">{pvc.accessMode || pvc.accessModes?.[0] || '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </ResourceSection>
  );
};

// ─── Services Section ─────────────────────────────────────────────────────────

const ServicesSection: React.FC<{ services: any[] }> = ({ services }) => {
  const [search, setSearch] = useState('');
  const [ns, setNs] = useState('all');
  const [typeFilter, setTypeFilter] = useState('all');

  const namespaces = useMemo(() => {
    const set = new Set<string>();
    services.forEach(s => s.namespace && set.add(s.namespace));
    return Array.from(set).sort();
  }, [services]);

  const filtered = useMemo(() => services.filter(s => {
    const matchNs = ns === 'all' || s.namespace === ns;
    const matchType = typeFilter === 'all' || s.type === typeFilter;
    const q = search.toLowerCase();
    return matchNs && matchType && (!q || (s.name || '').toLowerCase().includes(q));
  }), [services, search, ns, typeFilter]);

  const lbCount = services.filter(s => s.type === 'LoadBalancer').length;
  const nodePortCount = services.filter(s => s.type === 'NodePort').length;

  const serviceTypeColor = (type: string) => {
    if (type === 'LoadBalancer') return 'blue';
    if (type === 'NodePort') return 'purple';
    if (type === 'ExternalName') return 'amber';
    return 'gray';
  };

  return (
    <ResourceSection icon="fa-network-wired" color="bg-blue-500" title="Services" count={services.length}>
      <FilterBar search={search} onSearch={setSearch} namespaces={namespaces} namespace={ns} onNamespace={setNs} placeholder="Search services..." />
      <div className="flex gap-2 px-6 pb-3">
        {['all', 'ClusterIP', 'LoadBalancer', 'NodePort'].map(t => (
          <button
            key={t}
            onClick={() => setTypeFilter(t)}
            className={`px-3 py-1 rounded-lg text-xs font-bold transition-all ${typeFilter === t
              ? 'bg-blue-100 text-blue-700 border border-blue-200'
              : 'bg-gray-100 text-gray-500 hover:bg-gray-200'
            }`}
          >
            {t}{t === 'LoadBalancer' && lbCount > 0 && ` (${lbCount})`}
            {t === 'NodePort' && nodePortCount > 0 && ` (${nodePortCount})`}
          </button>
        ))}
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="bg-gray-50 border-b border-gray-100">
              <th className="text-left px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Name</th>
              <th className="text-left px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Namespace</th>
              <th className="text-left px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Type</th>
              <th className="text-left px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Cluster IP</th>
              <th className="text-left px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">External IP / Hostname</th>
              <th className="text-left px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Ports</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-50">
            {filtered.length === 0 ? (
              <tr><td colSpan={6} className="py-12 text-center text-gray-400 text-sm">No services found</td></tr>
            ) : filtered.map((svc: any, i: number) => {
              const externalIp = svc.externalIp || svc.loadBalancerIngress || svc.externalName || '—';
              const ports = Array.isArray(svc.ports)
                ? svc.ports.map((p: any) => typeof p === 'object' ? `${p.port || p.targetPort}/${p.protocol || 'TCP'}` : p).join(', ')
                : (svc.ports || '—');
              return (
                <tr key={i} className="hover:bg-gray-50 transition-colors">
                  <td className="px-6 py-3 font-semibold text-gray-900">{svc.name}</td>
                  <td className="px-6 py-3">
                    <span className="bg-gray-100 text-gray-600 px-2 py-0.5 rounded text-xs font-mono">{svc.namespace}</span>
                  </td>
                  <td className="px-6 py-3">
                    <StatusBadge text={svc.type || 'ClusterIP'} variant={serviceTypeColor(svc.type) as any} />
                  </td>
                  <td className="px-6 py-3 text-xs font-mono text-gray-500">{svc.clusterIp || svc.clusterIP || '—'}</td>
                  <td className="px-6 py-3 text-xs font-mono text-blue-600">
                    {externalIp !== '—' && externalIp ? (
                      <span className="font-medium">{externalIp}</span>
                    ) : <span className="text-gray-300">—</span>}
                  </td>
                  <td className="px-6 py-3 text-xs text-gray-500 font-mono">{ports}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </ResourceSection>
  );
};

// ─── Ingresses Section ────────────────────────────────────────────────────────

const IngressesSection: React.FC<{ ingresses: any[] }> = ({ ingresses }) => {
  const [search, setSearch] = useState('');
  const [ns, setNs] = useState('all');

  const namespaces = useMemo(() => {
    const set = new Set<string>();
    ingresses.forEach(i => i.namespace && set.add(i.namespace));
    return Array.from(set).sort();
  }, [ingresses]);

  const filtered = useMemo(() => ingresses.filter(i => {
    const matchNs = ns === 'all' || i.namespace === ns;
    const q = search.toLowerCase();
    return matchNs && (!q || (i.name || '').toLowerCase().includes(q) || (i.hosts || '').toLowerCase().includes(q));
  }), [ingresses, search, ns]);

  return (
    <ResourceSection icon="fa-globe" color="bg-indigo-500" title="Ingresses" count={ingresses.length}>
      <FilterBar search={search} onSearch={setSearch} namespaces={namespaces} namespace={ns} onNamespace={setNs} placeholder="Search ingresses or hosts..." />
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="bg-gray-50 border-b border-gray-100">
              <th className="text-left px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Name</th>
              <th className="text-left px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Namespace</th>
              <th className="text-left px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Hosts</th>
              <th className="text-left px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Class</th>
              <th className="text-left px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">TLS</th>
              <th className="text-left px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Address</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-50">
            {filtered.length === 0 ? (
              <tr><td colSpan={6} className="py-12 text-center text-gray-400 text-sm">No ingresses found</td></tr>
            ) : filtered.map((ing: any, i: number) => {
              const hosts = Array.isArray(ing.rules)
                ? ing.rules.map((r: any) => r.host || r).join(', ')
                : (ing.hosts || '—');
              const tlsEnabled = ing.tls === true || (Array.isArray(ing.tls) && ing.tls.length > 0);
              return (
                <tr key={i} className="hover:bg-gray-50 transition-colors">
                  <td className="px-6 py-3 font-semibold text-gray-900">{ing.name}</td>
                  <td className="px-6 py-3">
                    <span className="bg-gray-100 text-gray-600 px-2 py-0.5 rounded text-xs font-mono">{ing.namespace}</span>
                  </td>
                  <td className="px-6 py-3 text-xs text-indigo-700 font-mono font-medium">
                    {hosts && hosts !== '—' ? hosts : <span className="text-gray-300">*</span>}
                  </td>
                  <td className="px-6 py-3 text-xs text-gray-500">{ing.ingressClass || ing.className || 'default'}</td>
                  <td className="px-6 py-3">
                    {tlsEnabled
                      ? <StatusBadge text="TLS" variant="green" />
                      : <StatusBadge text="No TLS" variant="gray" />}
                  </td>
                  <td className="px-6 py-3 text-xs font-mono text-blue-600">{ing.address || ing.loadBalancerIngress || '—'}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </ResourceSection>
  );
};

// ─── K8s Events Section ───────────────────────────────────────────────────────

const EventsSection: React.FC<{ events: any[] }> = ({ events }) => {
  const [search, setSearch] = useState('');
  const [ns, setNs] = useState('all');
  const [typeFilter, setTypeFilter] = useState<'all' | 'Warning' | 'Normal'>('Warning');

  const namespaces = useMemo(() => {
    const set = new Set<string>();
    events.forEach(e => e.namespace && set.add(e.namespace));
    return Array.from(set).sort();
  }, [events]);

  const filtered = useMemo(() => events.filter(e => {
    const matchNs = ns === 'all' || e.namespace === ns;
    const matchType = typeFilter === 'all' || e.type === typeFilter;
    const q = search.toLowerCase();
    return matchNs && matchType && (!q ||
      (e.reason || '').toLowerCase().includes(q) ||
      (e.message || '').toLowerCase().includes(q) ||
      (e.involvedObjectName || e.name || '').toLowerCase().includes(q));
  }), [events, search, ns, typeFilter]);

  const warningCount = events.filter(e => e.type === 'Warning').length;

  // Sort: warnings first, then by count descending
  const sorted = useMemo(() => [...filtered].sort((a, b) => {
    if (a.type === 'Warning' && b.type !== 'Warning') return -1;
    if (a.type !== 'Warning' && b.type === 'Warning') return 1;
    return (b.count || 1) - (a.count || 1);
  }), [filtered]);

  return (
    <ResourceSection icon="fa-bell" color="bg-rose-500" title="Cluster Events" count={events.length}>
      <div className="flex flex-col sm:flex-row gap-3 px-6 py-4 bg-gray-50 border-b border-gray-100">
        {/* Type filter */}
        <div className="flex gap-2 flex-wrap">
          {(['all', 'Warning', 'Normal'] as const).map(t => (
            <button
              key={t}
              onClick={() => setTypeFilter(t)}
              className={`px-3 py-1.5 rounded-lg text-xs font-bold transition-all ${typeFilter === t
                ? t === 'Warning'
                  ? 'bg-red-100 text-red-700 border border-red-200'
                  : 'bg-blue-100 text-blue-700 border border-blue-200'
                : 'bg-white text-gray-500 border border-gray-200 hover:bg-gray-50'
              }`}
            >
              {t === 'all' ? 'All Events' : t}
              {t === 'Warning' && warningCount > 0 && (
                <span className="ml-1.5 bg-red-500 text-white rounded-full px-1.5 py-0.5 text-[10px]">{warningCount}</span>
              )}
            </button>
          ))}
        </div>
        <div className="relative flex-1 min-w-[200px]">
          <i className="fas fa-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-400 text-xs"></i>
          <input
            type="text"
            value={search}
            onChange={e => setSearch(e.target.value)}
            placeholder="Search reason, message, object..."
            className="w-full pl-8 pr-4 py-2 bg-white border border-gray-200 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 outline-none"
          />
        </div>
        {namespaces.length > 0 && (
          <select
            value={ns}
            onChange={e => setNs(e.target.value)}
            className="px-3 py-2 bg-white border border-gray-200 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 outline-none min-w-[160px]"
          >
            <option value="all">All Namespaces</option>
            {namespaces.map(n => <option key={n} value={n}>{n}</option>)}
          </select>
        )}
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="bg-gray-50 border-b border-gray-100">
              <th className="text-left px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider w-24">Type</th>
              <th className="text-left px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Reason</th>
              <th className="text-left px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Object</th>
              <th className="text-left px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Namespace</th>
              <th className="text-left px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Message</th>
              <th className="text-center px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Count</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-50">
            {sorted.length === 0 ? (
              <tr><td colSpan={6} className="py-12 text-center text-gray-400 text-sm">No events found</td></tr>
            ) : sorted.slice(0, 50).map((evt: any, i: number) => (
              <tr key={i} className={`hover:bg-gray-50 transition-colors ${evt.type === 'Warning' ? 'bg-red-50/30' : ''}`}>
                <td className="px-6 py-3">
                  {evt.type === 'Warning'
                    ? <StatusBadge text="Warning" variant="red" />
                    : <StatusBadge text="Normal" variant="green" />}
                </td>
                <td className="px-6 py-3 font-semibold text-gray-700 text-xs">{evt.reason || '—'}</td>
                <td className="px-6 py-3 text-xs font-mono text-gray-600">
                  {evt.involvedObjectKind && <span className="text-gray-400">{evt.involvedObjectKind}/</span>}
                  {evt.involvedObjectName || evt.name || '—'}
                </td>
                <td className="px-6 py-3">
                  <span className="bg-gray-100 text-gray-600 px-2 py-0.5 rounded text-xs font-mono">{evt.namespace}</span>
                </td>
                <td className="px-6 py-3 text-xs text-gray-600 max-w-xs">
                  <span className="line-clamp-2" title={evt.message}>{evt.message || '—'}</span>
                </td>
                <td className="px-6 py-3 text-center">
                  <span className={`font-bold text-sm ${(evt.count || 1) > 10 ? 'text-red-600' : 'text-gray-600'}`}>
                    {evt.count || 1}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {sorted.length > 50 && (
          <div className="px-6 py-3 text-xs text-gray-400 bg-gray-50 border-t">
            Showing 50 of {sorted.length} events (filtered to most recent warnings first)
          </div>
        )}
      </div>
    </ResourceSection>
  );
};

// ─── Jobs Section ─────────────────────────────────────────────────────────────

const JobsSection: React.FC<{ jobs: any[] }> = ({ jobs }) => {
  const [search, setSearch] = useState('');
  const [ns, setNs] = useState('all');

  const namespaces = useMemo(() => {
    const set = new Set<string>();
    jobs.forEach(j => j.namespace && set.add(j.namespace));
    return Array.from(set).sort();
  }, [jobs]);

  const filtered = useMemo(() => jobs.filter(j => {
    const matchNs = ns === 'all' || j.namespace === ns;
    const q = search.toLowerCase();
    return matchNs && (!q || (j.name || '').toLowerCase().includes(q));
  }), [jobs, search, ns]);

  if (jobs.length === 0) return null;

  return (
    <ResourceSection icon="fa-tasks" color="bg-cyan-500" title="Jobs" count={jobs.length}>
      <FilterBar search={search} onSearch={setSearch} namespaces={namespaces} namespace={ns} onNamespace={setNs} placeholder="Search jobs..." />
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="bg-gray-50 border-b border-gray-100">
              <th className="text-left px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Name</th>
              <th className="text-left px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Namespace</th>
              <th className="text-center px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Completions</th>
              <th className="text-center px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Active</th>
              <th className="text-center px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Failed</th>
              <th className="text-center px-6 py-3 text-xs font-bold text-gray-500 uppercase tracking-wider">Status</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-50">
            {filtered.length === 0 ? (
              <tr><td colSpan={6} className="py-12 text-center text-gray-400 text-sm">No jobs found</td></tr>
            ) : filtered.map((job: any, i: number) => {
              const succeeded = job.succeeded ?? job.completions ?? 0;
              const desired = job.desiredCompletions ?? job.completions ?? 1;
              const active = job.active ?? 0;
              const failed = job.failed ?? 0;
              const complete = succeeded >= desired;
              return (
                <tr key={i} className="hover:bg-gray-50 transition-colors">
                  <td className="px-6 py-3 font-semibold text-gray-900">{job.name}</td>
                  <td className="px-6 py-3">
                    <span className="bg-gray-100 text-gray-600 px-2 py-0.5 rounded text-xs font-mono">{job.namespace}</span>
                  </td>
                  <td className="px-6 py-3 text-center font-mono text-sm font-bold text-gray-700">{succeeded}/{desired}</td>
                  <td className="px-6 py-3 text-center font-bold text-blue-600">{active}</td>
                  <td className="px-6 py-3 text-center">
                    <span className={`font-bold ${failed > 0 ? 'text-red-600' : 'text-gray-400'}`}>{failed}</span>
                  </td>
                  <td className="px-6 py-3 text-center">
                    {active > 0
                      ? <StatusBadge text="Running" variant="blue" />
                      : complete
                        ? <StatusBadge text="Complete" variant="green" />
                        : failed > 0
                          ? <StatusBadge text="Failed" variant="red" />
                          : <StatusBadge text="Pending" variant="amber" />}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </ResourceSection>
  );
};

// ─── Summary cards at the top ─────────────────────────────────────────────────

const ResourceSummary: React.FC<{ dashboard: any }> = ({ dashboard }) => {
  const stats = [
    { label: 'HPAs', value: (dashboard.k8sHpas || []).length, icon: 'fa-expand-arrows-alt', color: 'text-purple-600', bg: 'bg-purple-50' },
    { label: 'Services', value: (dashboard.k8sServices || []).length, icon: 'fa-network-wired', color: 'text-blue-600', bg: 'bg-blue-50' },
    { label: 'Ingresses', value: (dashboard.k8sIngresses || []).length, icon: 'fa-globe', color: 'text-indigo-600', bg: 'bg-indigo-50' },
    { label: 'PVCs', value: (dashboard.k8sPvcs || []).length, icon: 'fa-hdd', color: 'text-teal-600', bg: 'bg-teal-50' },
    { label: 'Events', value: (dashboard.k8sEvents || []).length, icon: 'fa-bell', color: 'text-rose-600', bg: 'bg-rose-50' },
    {
      label: 'Warnings',
      value: (dashboard.k8sEvents || []).filter((e: any) => e.type === 'Warning').length,
      icon: 'fa-exclamation-triangle',
      color: 'text-amber-600',
      bg: 'bg-amber-50'
    },
  ];

  return (
    <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-4">
      {stats.map(({ label, value, icon, color, bg }) => (
        <div key={label} className="bg-white rounded-xl border border-gray-200 shadow-sm p-4">
          <div className="flex items-center gap-2 mb-2">
            <div className={`w-8 h-8 rounded-lg ${bg} flex items-center justify-center`}>
              <i className={`fas ${icon} ${color} text-sm`}></i>
            </div>
            <span className="text-xs font-semibold text-gray-500">{label}</span>
          </div>
          <div className="text-2xl font-black text-gray-900">{value}</div>
        </div>
      ))}
    </div>
  );
};

// ═══════════════════════════════════════════════════════════════════════════════
// MAIN COMPONENT
// ═══════════════════════════════════════════════════════════════════════════════

const EksResourcesTab: React.FC<{ eksData: any }> = ({ eksData }) => {
  const { dashboard } = eksData;

  if (!dashboard) return null;

  const hpas: any[] = dashboard.k8sHpas || [];
  const pvcs: any[] = dashboard.k8sPvcs || [];
  const services: any[] = dashboard.k8sServices || [];
  const ingresses: any[] = dashboard.k8sIngresses || [];
  const events: any[] = dashboard.k8sEvents || [];
  const jobs: any[] = dashboard.k8sJobs || [];

  const hasAnyData = hpas.length > 0 || pvcs.length > 0 || services.length > 0 || ingresses.length > 0 || events.length > 0;

  if (!hasAnyData) {
    return (
      <EmptyState
        icon="fas fa-layer-group"
        title="No K8s resource data yet"
        subtitle="Install xamops-agent with COLLECTOR_K8S_RESOURCES=true and wait for the first push (up to 5 min)."
      />
    );
  }

  return (
    <div className="space-y-6">
      {/* Summary row */}
      <ResourceSummary dashboard={dashboard} />

      {/* Info banner — agent source */}
      <div className="bg-blue-50 border border-blue-200 rounded-lg px-5 py-3 flex items-center gap-3 text-sm text-blue-800">
        <i className="fas fa-robot text-blue-500"></i>
        <span>
          Data sourced from <strong>xamops-agent</strong> k8s-resources collector — full cluster snapshot pushed every 5 minutes.
          Shows HPAs, PVCs, Services, Ingresses, Jobs, and K8s Events for all namespaces.
        </span>
      </div>

      {/* Sections */}
      {events.length > 0 && <EventsSection events={events} />}
      {hpas.length > 0 && <HpaSection hpas={hpas} />}
      {services.length > 0 && <ServicesSection services={services} />}
      {ingresses.length > 0 && <IngressesSection ingresses={ingresses} />}
      {pvcs.length > 0 && <PvcSection pvcs={pvcs} />}
      {jobs.length > 0 && <JobsSection jobs={jobs} />}
    </div>
  );
};

export default EksResourcesTab;
