import React, { useEffect, useState } from 'react';
import { Command } from 'cmdk';
import { useNavigate } from 'react-router-dom';
import {
  HiOutlineViewGrid,
  HiOutlineLightningBolt,
  HiOutlineCurrencyDollar,
  HiOutlineTrash,
  HiOutlineScale,
  HiOutlineBookmark,
  HiOutlineShieldCheck,
  HiOutlineTrendingUp,
  HiOutlineBell,
  HiOutlineServer,
  HiOutlineTerminal,
  HiOutlineCode,
  HiOutlineDocumentText,
  HiOutlineUser,
  HiOutlineUserGroup,
  HiOutlineCog,
  HiOutlineCalculator,
  HiOutlineTag,
  HiOutlineGlobe,
  HiOutlineSearch,
  HiOutlineChip,
} from 'react-icons/hi';

interface RouteCommand {
  path: string;
  label: string;
  group: string;
  icon: React.ElementType;
  keywords?: string;
}

const COMMANDS: RouteCommand[] = [
  // Dashboards
  { path: '/unified-dashboard',   label: 'Unified Dashboard',        group: 'Dashboards',       icon: HiOutlineViewGrid },
  { path: '/dashboard',           label: 'Live Dashboard',            group: 'Dashboards',       icon: HiOutlineLightningBolt },
  { path: '/gcp-dashboard',       label: 'GCP Dashboard',             group: 'Dashboards',       icon: HiOutlineViewGrid },
  { path: '/azure-dashboard',     label: 'Azure Dashboard',           group: 'Dashboards',       icon: HiOutlineViewGrid },

  // AWS
  { path: '/finops',              label: 'AWS FinOps',                group: 'AWS',              icon: HiOutlineCurrencyDollar },
  { path: '/cost',                label: 'AWS Cost',                  group: 'AWS',              icon: HiOutlineCurrencyDollar },
  { path: '/waste',               label: 'AWS Waste',                 group: 'AWS',              icon: HiOutlineTrash },
  { path: '/rightsizing',         label: 'AWS Rightsizing',           group: 'AWS',              icon: HiOutlineScale },
  { path: '/reservation',         label: 'AWS Reservations',          group: 'AWS',              icon: HiOutlineBookmark },
  { path: '/ebs-rightsizing',     label: 'EBS Rightsizing',           group: 'AWS',              icon: HiOutlineScale },
  { path: '/flex-compute',        label: 'Flex Compute',              group: 'AWS',              icon: HiOutlineServer },
  { path: '/spot-automation',     label: 'Spot Automation',           group: 'AWS',              icon: HiOutlineLightningBolt, keywords: 'autospotting' },
  { path: '/asg-detail',          label: 'ASG Detail',                group: 'AWS',              icon: HiOutlineServer },
  { path: '/asg-config',          label: 'ASG Config',                group: 'AWS',              icon: HiOutlineCog },
  { path: '/cloudsitter',         label: 'CloudSitter',               group: 'AWS',              icon: HiOutlineServer },
  { path: '/gcp-cloudsitter',     label: 'CloudSitter (GCP)',         group: 'GCP',              icon: HiOutlineServer },
  { path: '/azure-cloudsitter',   label: 'CloudSitter (Azure)',       group: 'Azure',            icon: HiOutlineServer },
  { path: '/cloudk8s',            label: 'Cloud Kubernetes (AWS)',    group: 'AWS',              icon: HiOutlineChip, keywords: 'eks k8s kubernetes' },
  { path: '/eks-details',         label: 'EKS Details',               group: 'AWS',              icon: HiOutlineChip },
  { path: '/cloudlist',           label: 'AWS Cloud List',            group: 'AWS',              icon: HiOutlineServer, keywords: 'resources inventory' },
  { path: '/cloudmap',            label: 'AWS Cloud Map',             group: 'AWS',              icon: HiOutlineGlobe, keywords: 'topology' },
  { path: '/cloudshell',          label: 'Cloud Shell',               group: 'AWS',              icon: HiOutlineTerminal, keywords: 'terminal' },
  { path: '/security',            label: 'AWS Security',              group: 'AWS',              icon: HiOutlineShieldCheck },
  { path: '/war',                 label: 'WAR — Attack-surface Review', group: 'AWS',            icon: HiOutlineShieldCheck, keywords: 'war attack surface crypto mining miner exposure hardening' },
  { path: '/gcp-war',             label: 'GCP WAR — Attack-surface Review', group: 'GCP',        icon: HiOutlineShieldCheck, keywords: 'war attack surface crypto mining miner exposure hardening' },
  { path: '/azure-war',           label: 'Azure WAR — Attack-surface Review', group: 'Azure',    icon: HiOutlineShieldCheck, keywords: 'war attack surface crypto mining miner exposure hardening' },
  { path: '/performance',         label: 'AWS Performance',           group: 'AWS',              icon: HiOutlineTrendingUp },
  { path: '/alerts',              label: 'AWS Alerts',                group: 'AWS',              icon: HiOutlineBell },
  { path: '/grafana-dashboard',   label: 'Grafana Dashboard',         group: 'AWS',              icon: HiOutlineTrendingUp },
  { path: '/cloudshell',          label: 'Cloud Shell',               group: 'AWS',              icon: HiOutlineTerminal, keywords: 'terminal' },

  // GCP
  { path: '/gcp-finops',          label: 'GCP FinOps',               group: 'GCP',              icon: HiOutlineCurrencyDollar },
  { path: '/gcp-cost',            label: 'GCP Cost',                  group: 'GCP',              icon: HiOutlineCurrencyDollar },
  { path: '/gcp-waste',           label: 'GCP Waste',                 group: 'GCP',              icon: HiOutlineTrash },
  { path: '/gcp-rightsizing',     label: 'GCP Rightsizing',           group: 'GCP',              icon: HiOutlineScale },
  { path: '/gcp-reservations',    label: 'GCP Reservations',          group: 'GCP',              icon: HiOutlineBookmark },
  { path: '/gcp-cloudk8s',        label: 'GCP Kubernetes',            group: 'GCP',              icon: HiOutlineChip, keywords: 'gke k8s' },
  { path: '/gke-details',         label: 'GKE Details',               group: 'GCP',              icon: HiOutlineChip },
  { path: '/gcp-cloudlist',       label: 'GCP Cloud List',            group: 'GCP',              icon: HiOutlineServer },
  { path: '/gcp-cloudmap',        label: 'GCP Cloud Map',             group: 'GCP',              icon: HiOutlineGlobe },
  { path: '/gcp-security',        label: 'GCP Security',              group: 'GCP',              icon: HiOutlineShieldCheck },
  { path: '/gcp-performance',     label: 'GCP Performance',           group: 'GCP',              icon: HiOutlineTrendingUp },
  { path: '/gcp-alerts',          label: 'GCP Alerts',                group: 'GCP',              icon: HiOutlineBell },
  { path: '/billops-gcp-billing', label: 'GCP Billing',               group: 'GCP',              icon: HiOutlineDocumentText },
  { path: '/gcp-autospotting',    label: 'GCP Spot Automation',       group: 'GCP',              icon: HiOutlineLightningBolt },
  { path: '/gcp-dataops',         label: 'GCP Data Operations',       group: 'GCP',              icon: HiOutlineServer, keywords: 'dataops' },
  { path: '/gcp-disk-rightsizing', label: 'GCP Disk Rightsizing',     group: 'GCP',              icon: HiOutlineScale, keywords: 'disk persistent volume' },

  // Azure
  { path: '/azure-finops',        label: 'Azure FinOps',              group: 'Azure',            icon: HiOutlineCurrencyDollar },
  { path: '/azure-cost',          label: 'Azure Cost',                group: 'Azure',            icon: HiOutlineCurrencyDollar },
  { path: '/azure-waste',         label: 'Azure Waste',               group: 'Azure',            icon: HiOutlineTrash },
  { path: '/azure-rightsizing',   label: 'Azure Rightsizing',         group: 'Azure',            icon: HiOutlineScale },
  { path: '/azure-reservations',  label: 'Azure Reservations',        group: 'Azure',            icon: HiOutlineBookmark },
  { path: '/azure-cloudk8s',      label: 'Azure Kubernetes',          group: 'Azure',            icon: HiOutlineChip, keywords: 'aks k8s' },
  { path: '/aks-details',         label: 'AKS Details',               group: 'Azure',            icon: HiOutlineChip },
  { path: '/azure-cloudlist',     label: 'Azure Cloud List',          group: 'Azure',            icon: HiOutlineServer },
  { path: '/azure-security',      label: 'Azure Security',            group: 'Azure',            icon: HiOutlineShieldCheck },
  { path: '/azure-performance',   label: 'Azure Performance',         group: 'Azure',            icon: HiOutlineTrendingUp },
  { path: '/azure-alerts',        label: 'Azure Alerts',              group: 'Azure',            icon: HiOutlineBell },
  { path: '/azure-autospotting',  label: 'Azure Spot Automation',     group: 'Azure',            icon: HiOutlineLightningBolt },
  { path: '/azure-disk-rightsizing', label: 'Azure Disk Rightsizing', group: 'Azure',            icon: HiOutlineScale, keywords: 'disk managed volume' },
  { path: '/azure-dataops',       label: 'Azure Data Operations',     group: 'Azure',            icon: HiOutlineServer, keywords: 'dataops dbops' },

  // DevOps & Tools
  { path: '/account-manager',     label: 'Account Manager',           group: 'DevOps & Tools',   icon: HiOutlineCog, keywords: 'accounts cloud' },
  { path: '/add-account',         label: 'Add AWS Account',           group: 'DevOps & Tools',   icon: HiOutlineCog },
  { path: '/add-gcp-account',     label: 'Add GCP Account',           group: 'DevOps & Tools',   icon: HiOutlineCog },
  { path: '/add-github-config',   label: 'GitHub Config',             group: 'DevOps & Tools',   icon: HiOutlineCode },
  { path: '/devops-in-the-box',   label: 'DevOps In The Box',         group: 'DevOps & Tools',   icon: HiOutlineCode, keywords: 'cloudformation' },
  { path: '/cicd-pipelines',      label: 'CI/CD Pipelines',           group: 'DevOps & Tools',   icon: HiOutlineLightningBolt },
  { path: '/sonarqube',           label: 'SonarQube',                 group: 'DevOps & Tools',   icon: HiOutlineShieldCheck, keywords: 'code quality' },
  { path: '/aiops',               label: 'AI Operations',             group: 'DevOps & Tools',   icon: HiOutlineLightningBolt, keywords: 'ai advisor' },
  { path: '/dataops',             label: 'Data Operations',           group: 'DevOps & Tools',   icon: HiOutlineServer },
  { path: '/observability',       label: 'Observability',             group: 'DevOps & Tools',   icon: HiOutlineTrendingUp, keywords: 'openobserve monitoring' },
  { path: '/complianceops',       label: 'Compliance Operations',     group: 'DevOps & Tools',   icon: HiOutlineShieldCheck },
  { path: '/resource-labels',     label: 'Resource Labels',           group: 'DevOps & Tools',   icon: HiOutlineTag },
  { path: '/calculator',          label: 'Cost Calculator',           group: 'DevOps & Tools',   icon: HiOutlineCalculator },

  // BillOps
  { path: '/billops-dashboard',   label: 'BillOps Dashboard',         group: 'BillOps',          icon: HiOutlineViewGrid },
  { path: '/billops-billing',     label: 'Billing',                   group: 'BillOps',          icon: HiOutlineCurrencyDollar },
  { path: '/billops-invoices',    label: 'Invoices',                  group: 'BillOps',          icon: HiOutlineDocumentText },
  { path: '/billops-credits',     label: 'Credits',                   group: 'BillOps',          icon: HiOutlineCurrencyDollar },
  { path: '/billops-tickets',     label: 'BillOps Tickets',           group: 'BillOps',          icon: HiOutlineDocumentText },
  { path: '/billops-agreements',  label: 'Agreements',                group: 'BillOps',          icon: HiOutlineDocumentText },
  { path: '/marketplace-purchases', label: 'Marketplace Purchases',   group: 'BillOps',          icon: HiOutlineTag },
  { path: '/workspace-licenses',  label: 'Workspace Licenses',        group: 'BillOps',          icon: HiOutlineTag },

  // Admin
  { path: '/billops-admin-dashboard',       label: 'Admin Dashboard',         group: 'Admin',  icon: HiOutlineViewGrid },
  { path: '/billops-support-dashboard',     label: 'Support Dashboard',       group: 'Admin',  icon: HiOutlineUserGroup },
  { path: '/billops-admin-invoices',        label: 'Admin Invoices',          group: 'Admin',  icon: HiOutlineDocumentText },
  { path: '/billops-admin-credits',         label: 'Admin Credits',           group: 'Admin',  icon: HiOutlineCurrencyDollar },
  { path: '/billops-admin-tickets',         label: 'Admin Tickets',           group: 'Admin',  icon: HiOutlineDocumentText },
  { path: '/billops-admin-agreements',      label: 'Admin Agreements',        group: 'Admin',  icon: HiOutlineDocumentText },
  { path: '/billops-admin-historical-billing', label: 'Historical Billing',   group: 'Admin',  icon: HiOutlineCurrencyDollar },
  { path: '/billops-client-details',        label: 'Client Details',          group: 'Admin',  icon: HiOutlineUser },
  { path: '/billops-admin-user-manager',    label: 'Admin User Manager',      group: 'Admin',  icon: HiOutlineUserGroup },
  { path: '/autospotting-admin',            label: 'AutoSpotting Admin',      group: 'Admin',  icon: HiOutlineLightningBolt },

  // Settings & Profile
  { path: '/tickets',             label: 'Support Tickets',           group: 'Settings',         icon: HiOutlineDocumentText },
  // Points straight at the tab rather than the retired /user-management route, so the palette
  // does not cost a redirect. Keywords keep the old name findable.
  { path: '/security?tab=identity', label: 'Identity & Access (IAM)',  group: 'Settings',         icon: HiOutlineUserGroup, keywords: 'user management iam users roles permissions privilege' },
  { path: '/my-profile',          label: 'My Profile',                group: 'Settings',         icon: HiOutlineUser },
];

const GROUPS = ['Dashboards', 'AWS', 'GCP', 'Azure', 'DevOps & Tools', 'BillOps', 'Admin', 'Settings'];


export const CommandPalette: React.FC = () => {
  const [open, setOpen] = useState(false);
  const navigate = useNavigate();

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault();
        setOpen(prev => !prev);
      }
      if (e.key === 'Escape') {
        setOpen(false);
      }
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, []);

  const handleSelect = (path: string) => {
    navigate(path);
    setOpen(false);
  };

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-[9999] flex items-start justify-center pt-[15vh]">
      {/* Backdrop */}
      <div
        className="absolute inset-0 bg-black/40 backdrop-blur-sm"
        onClick={() => setOpen(false)}
      />

      {/* Palette */}
      <div className="relative w-full max-w-xl mx-4 bg-white rounded-xl shadow-2xl border border-gray-200 overflow-hidden">
        <Command
          className="flex flex-col"
          style={{ maxHeight: '65vh' }}
          filter={(value, search) => {
            if (!search) return 1;
            const term = search.toLowerCase();
            return value.toLowerCase().includes(term) ? 1 : 0;
          }}
        >
          {/* Search Input */}
          <div className="flex items-center gap-3 px-4 py-3 border-b border-gray-100">
            <HiOutlineSearch className="w-4 h-4 text-gray-400 shrink-0" />
            <Command.Input
              autoFocus
              placeholder="Search pages, features..."
              className="flex-1 bg-transparent text-sm text-gray-800 placeholder-gray-400 outline-none"
            />
            <kbd className="hidden sm:inline-flex items-center gap-1 px-2 py-0.5 rounded border border-gray-200 text-[10px] text-gray-400 font-mono">
              ESC
            </kbd>
          </div>

          {/* Results */}
          <Command.List className="overflow-y-auto py-2" style={{ maxHeight: 'calc(65vh - 56px)' }}>
            <Command.Empty className="py-8 text-center text-sm text-gray-400">
              No pages found.
            </Command.Empty>

            {GROUPS.map(group => {
              const items = COMMANDS.filter(c => c.group === group);
              return (
                <Command.Group
                  key={group}
                  heading={group}
                  className="[&_[cmdk-group-heading]]:px-4 [&_[cmdk-group-heading]]:py-1.5 [&_[cmdk-group-heading]]:text-[11px] [&_[cmdk-group-heading]]:font-semibold [&_[cmdk-group-heading]]:text-gray-400 [&_[cmdk-group-heading]]:uppercase [&_[cmdk-group-heading]]:tracking-wider"
                >
                  {items.map(cmd => {
                    const Icon = cmd.icon;
                    return (
                      <Command.Item
                        key={cmd.path}
                        value={`${cmd.label} ${cmd.group} ${cmd.keywords ?? ''}`}
                        onSelect={() => handleSelect(cmd.path)}
                        className="flex items-center gap-3 px-4 py-2.5 cursor-pointer text-sm text-gray-700
                          data-[selected=true]:bg-indigo-50 data-[selected=true]:text-indigo-700
                          hover:bg-gray-50 transition-colors"
                      >
                        <span className="w-7 h-7 rounded-md bg-gray-100 flex items-center justify-center shrink-0
                          data-[selected=true]:bg-indigo-100 group-data-[selected=true]:bg-indigo-100">
                          <Icon className="w-3.5 h-3.5 text-gray-500" />
                        </span>
                        <span className="flex-1 font-medium">{cmd.label}</span>
                        <span className="text-xs text-gray-300">{cmd.path}</span>
                      </Command.Item>
                    );
                  })}
                </Command.Group>
              );
            })}
          </Command.List>

          {/* Footer hint */}
          <div className="flex items-center justify-between px-4 py-2 border-t border-gray-100 bg-gray-50">
            <div className="flex items-center gap-3 text-[11px] text-gray-400">
              <span className="flex items-center gap-1">
                <kbd className="px-1.5 py-0.5 rounded border border-gray-200 font-mono bg-white">↑↓</kbd>
                navigate
              </span>
              <span className="flex items-center gap-1">
                <kbd className="px-1.5 py-0.5 rounded border border-gray-200 font-mono bg-white">↵</kbd>
                open
              </span>
              <span className="flex items-center gap-1">
                <kbd className="px-1.5 py-0.5 rounded border border-gray-200 font-mono bg-white">ESC</kbd>
                close
              </span>
            </div>
            <span className="text-[11px] text-gray-300">
              {COMMANDS.length} pages
            </span>
          </div>
        </Command>
      </div>
    </div>
  );
};
