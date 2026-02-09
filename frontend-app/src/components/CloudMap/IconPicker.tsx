/**
 * IconPicker Component
 *
 * A comprehensive icon picker similar to draw.io with:
 * - Multiple icon libraries (AWS, General, Network, Security, etc.)
 * - Search functionality
 * - Category filtering
 * - Drag-and-drop support
 */

import React, { useState, useMemo } from 'react';
import {
  // General icons
  FiCloud, FiServer, FiDatabase, FiHardDrive, FiCpu, FiActivity,
  FiLock, FiShield, FiKey, FiUsers, FiUser, FiMail,
  FiGlobe, FiWifi, FiRadio, FiAnchor, FiBox, FiPackage,
  FiArchive, FiFolder, FiFile, FiImage, FiCode, FiTerminal,
  FiSettings, FiTool, FiSliders, FiPieChart, FiBarChart, FiTrendingUp,
  FiZap, FiSun, FiMoon, FiStar, FiHeart,
  FiShoppingCart, FiCreditCard, FiDollarSign, FiTruck, FiPhone, FiMonitor,
} from 'react-icons/fi';
import {
  // AWS/Cloud icons - using verified icon names
  SiAmazon, SiKubernetes, SiDocker, SiTerraform,
  SiJenkins, SiGithubactions, SiGitlab, SiPrometheus, SiGrafana, SiElasticsearch,
  SiRedis, SiMongodb, SiPostgresql, SiMysql, SiNginx, SiApache,
} from 'react-icons/si';
import {
  // Material Design icons
  MdStorage, MdSecurity, MdVpnKey, MdCloudQueue, MdRouter, MdDeviceHub,
  MdMemory, MdDns, MdWeb, MdApi, MdIntegrationInstructions, MdBuild,
} from 'react-icons/md';
import {
  // Business icons
  BsBuilding, BsPeople, BsGear, BsGraphUp, BsShop, BsCart,
  BsCurrencyDollar, BsFileEarmark, BsClipboard, BsCalendar,
} from 'react-icons/bs';
import {
  // Hero icons
  HiOutlineDocumentText, HiOutlineChartBar, HiOutlineCog, HiOutlineUsers,
  HiOutlineShieldCheck, HiOutlineCloud,
} from 'react-icons/hi';

export interface IconData {
  id: string;
  name: string;
  icon: React.ComponentType<{ className?: string; size?: number }>;
  category: string;
  tags: string[];
  color?: string;
}

// Comprehensive icon library organized by categories
const iconLibrary: IconData[] = [
  // Compute
  { id: 'server-1', name: 'Server', icon: FiServer, category: 'Compute', tags: ['server', 'compute', 'host'], color: 'orange' },
  { id: 'cpu-1', name: 'CPU', icon: FiCpu, category: 'Compute', tags: ['cpu', 'processor', 'compute'], color: 'orange' },
  { id: 'container-1', name: 'Container', icon: SiDocker, category: 'Compute', tags: ['docker', 'container', 'kubernetes'], color: 'blue' },
  { id: 'k8s-1', name: 'Kubernetes', icon: SiKubernetes, category: 'Compute', tags: ['kubernetes', 'k8s', 'orchestration'], color: 'blue' },

  // Storage
  { id: 'database-1', name: 'Database', icon: FiDatabase, category: 'Storage', tags: ['database', 'db', 'storage'], color: 'sky' },
  { id: 'harddrive-1', name: 'Hard Drive', icon: FiHardDrive, category: 'Storage', tags: ['disk', 'storage', 'volume'], color: 'gray' },
  { id: 'storage-1', name: 'Storage', icon: MdStorage, category: 'Storage', tags: ['storage', 'disk', 'data'], color: 'teal' },
  { id: 'archive-1', name: 'Archive', icon: FiArchive, category: 'Storage', tags: ['archive', 'backup', 'storage'], color: 'lime' },
  { id: 'mongodb-1', name: 'MongoDB', icon: SiMongodb, category: 'Storage', tags: ['mongodb', 'nosql', 'database'], color: 'green' },
  { id: 'postgres-1', name: 'PostgreSQL', icon: SiPostgresql, category: 'Storage', tags: ['postgresql', 'postgres', 'database'], color: 'blue' },
  { id: 'mysql-1', name: 'MySQL', icon: SiMysql, category: 'Storage', tags: ['mysql', 'database', 'sql'], color: 'blue' },
  { id: 'redis-1', name: 'Redis', icon: SiRedis, category: 'Storage', tags: ['redis', 'cache', 'memory'], color: 'red' },

  // Network
  { id: 'cloud-1', name: 'Cloud', icon: FiCloud, category: 'Network', tags: ['cloud', 'network', 'vpc'], color: 'green' },
  { id: 'globe-1', name: 'Globe', icon: FiGlobe, category: 'Network', tags: ['internet', 'global', 'network'], color: 'cyan' },
  { id: 'wifi-1', name: 'WiFi', icon: FiWifi, category: 'Network', tags: ['wifi', 'wireless', 'network'], color: 'indigo' },
  { id: 'router-1', name: 'Router', icon: MdRouter, category: 'Network', tags: ['router', 'network', 'gateway'], color: 'purple' },
  { id: 'hub-1', name: 'Hub', icon: MdDeviceHub, category: 'Network', tags: ['hub', 'switch', 'network'], color: 'emerald' },
  { id: 'dns-1', name: 'DNS', icon: MdDns, category: 'Network', tags: ['dns', 'domain', 'network'], color: 'orange' },
  { id: 'nginx-1', name: 'NGINX', icon: SiNginx, category: 'Network', tags: ['nginx', 'load balancer', 'proxy'], color: 'green' },
  { id: 'apache-1', name: 'Apache', icon: SiApache, category: 'Network', tags: ['apache', 'web server', 'http'], color: 'red' },

  // Security
  { id: 'lock-1', name: 'Lock', icon: FiLock, category: 'Security', tags: ['security', 'lock', 'encryption'], color: 'yellow' },
  { id: 'shield-1', name: 'Shield', icon: FiShield, category: 'Security', tags: ['shield', 'security', 'protection'], color: 'yellow' },
  { id: 'key-1', name: 'Key', icon: FiKey, category: 'Security', tags: ['key', 'kms', 'encryption'], color: 'amber' },
  { id: 'vpnkey-1', name: 'VPN Key', icon: MdVpnKey, category: 'Security', tags: ['vpn', 'key', 'security'], color: 'yellow' },
  { id: 'security-1', name: 'Security', icon: MdSecurity, category: 'Security', tags: ['security', 'protection', 'shield'], color: 'red' },
  { id: 'shield-check-1', name: 'Shield Check', icon: HiOutlineShieldCheck, category: 'Security', tags: ['security', 'verified', 'protected'], color: 'green' },

  // Users & Access
  { id: 'users-1', name: 'Users', icon: FiUsers, category: 'Users', tags: ['users', 'team', 'people'], color: 'orange' },
  { id: 'user-1', name: 'User', icon: FiUser, category: 'Users', tags: ['user', 'person', 'account'], color: 'indigo' },
  { id: 'people-1', name: 'People', icon: BsPeople, category: 'Users', tags: ['people', 'team', 'group'], color: 'purple' },
  { id: 'users-cog-1', name: 'User Management', icon: HiOutlineUsers, category: 'Users', tags: ['users', 'management', 'admin'], color: 'slate' },

  // Cloud Providers
  { id: 'aws-1', name: 'AWS', icon: SiAmazon, category: 'Cloud', tags: ['aws', 'amazon', 'cloud'], color: 'orange' },
  { id: 'azure-1', name: 'Azure', icon: HiOutlineCloud, category: 'Cloud', tags: ['azure', 'microsoft', 'cloud'], color: 'blue' },
  { id: 'gcp-1', name: 'GCP', icon: HiOutlineCloud, category: 'Cloud', tags: ['gcp', 'google', 'cloud'], color: 'red' },
  { id: 'cloud-queue-1', name: 'Cloud Queue', icon: MdCloudQueue, category: 'Cloud', tags: ['queue', 'cloud', 'messaging'], color: 'pink' },

  // DevOps
  { id: 'jenkins-1', name: 'Jenkins', icon: SiJenkins, category: 'DevOps', tags: ['jenkins', 'ci', 'cd'], color: 'red' },
  { id: 'github-actions-1', name: 'GitHub Actions', icon: SiGithubactions, category: 'DevOps', tags: ['github', 'actions', 'ci'], color: 'slate' },
  { id: 'gitlab-1', name: 'GitLab', icon: SiGitlab, category: 'DevOps', tags: ['gitlab', 'ci', 'git'], color: 'orange' },
  { id: 'terraform-1', name: 'Terraform', icon: SiTerraform, category: 'DevOps', tags: ['terraform', 'iac', 'infrastructure'], color: 'violet' },

  // Monitoring
  { id: 'activity-1', name: 'Activity', icon: FiActivity, category: 'Monitoring', tags: ['monitoring', 'activity', 'metrics'], color: 'green' },
  { id: 'prometheus-1', name: 'Prometheus', icon: SiPrometheus, category: 'Monitoring', tags: ['prometheus', 'monitoring', 'metrics'], color: 'orange' },
  { id: 'grafana-1', name: 'Grafana', icon: SiGrafana, category: 'Monitoring', tags: ['grafana', 'dashboard', 'monitoring'], color: 'amber' },
  { id: 'elastic-1', name: 'Elasticsearch', icon: SiElasticsearch, category: 'Monitoring', tags: ['elastic', 'search', 'logs'], color: 'yellow' },
  { id: 'chart-bar-1', name: 'Chart', icon: HiOutlineChartBar, category: 'Monitoring', tags: ['chart', 'analytics', 'metrics'], color: 'indigo' },

  // Application
  { id: 'box-1', name: 'Box', icon: FiBox, category: 'Application', tags: ['box', 'app', 'package'], color: 'rose' },
  { id: 'package-1', name: 'Package', icon: FiPackage, category: 'Application', tags: ['package', 'app', 'module'], color: 'violet' },
  { id: 'code-1', name: 'Code', icon: FiCode, category: 'Application', tags: ['code', 'development', 'programming'], color: 'slate' },
  { id: 'terminal-1', name: 'Terminal', icon: FiTerminal, category: 'Application', tags: ['terminal', 'cli', 'command'], color: 'gray' },
  { id: 'api-1', name: 'API', icon: MdApi, category: 'Application', tags: ['api', 'interface', 'integration'], color: 'amber' },
  { id: 'web-1', name: 'Web', icon: MdWeb, category: 'Application', tags: ['web', 'website', 'frontend'], color: 'blue' },

  // Management
  { id: 'settings-1', name: 'Settings', icon: FiSettings, category: 'Management', tags: ['settings', 'config', 'management'], color: 'slate' },
  { id: 'tool-1', name: 'Tool', icon: FiTool, category: 'Management', tags: ['tool', 'utility', 'management'], color: 'gray' },
  { id: 'sliders-1', name: 'Sliders', icon: FiSliders, category: 'Management', tags: ['sliders', 'control', 'settings'], color: 'indigo' },
  { id: 'gear-1', name: 'Gear', icon: BsGear, category: 'Management', tags: ['gear', 'config', 'settings'], color: 'slate' },
  { id: 'cog-1', name: 'Cog', icon: HiOutlineCog, category: 'Management', tags: ['cog', 'settings', 'config'], color: 'gray' },
  { id: 'build-1', name: 'Build', icon: MdBuild, category: 'Management', tags: ['build', 'tool', 'maintenance'], color: 'orange' },

  // Analytics
  { id: 'pie-chart-1', name: 'Pie Chart', icon: FiPieChart, category: 'Analytics', tags: ['chart', 'analytics', 'data'], color: 'cyan' },
  { id: 'bar-chart-1', name: 'Bar Chart', icon: FiBarChart, category: 'Analytics', tags: ['chart', 'analytics', 'data'], color: 'teal' },
  { id: 'trending-up-1', name: 'Trending Up', icon: FiTrendingUp, category: 'Analytics', tags: ['trending', 'growth', 'analytics'], color: 'emerald' },
  { id: 'graph-up-1', name: 'Graph Up', icon: BsGraphUp, category: 'Analytics', tags: ['graph', 'growth', 'analytics'], color: 'green' },

  // Business
  { id: 'building-1', name: 'Building', icon: BsBuilding, category: 'Business', tags: ['building', 'company', 'organization'], color: 'gray' },
  { id: 'shop-1', name: 'Shop', icon: BsShop, category: 'Business', tags: ['shop', 'store', 'business'], color: 'purple' },
  { id: 'cart-1', name: 'Cart', icon: FiShoppingCart, category: 'Business', tags: ['cart', 'shopping', 'ecommerce'], color: 'pink' },
  { id: 'cart-2', name: 'Shopping Cart', icon: BsCart, category: 'Business', tags: ['cart', 'shopping', 'ecommerce'], color: 'fuchsia' },
  { id: 'currency-1', name: 'Currency', icon: BsCurrencyDollar, category: 'Business', tags: ['currency', 'money', 'finance'], color: 'green' },
  { id: 'dollar-1', name: 'Dollar', icon: FiDollarSign, category: 'Business', tags: ['dollar', 'money', 'payment'], color: 'emerald' },
  { id: 'credit-card-1', name: 'Credit Card', icon: FiCreditCard, category: 'Business', tags: ['card', 'payment', 'finance'], color: 'indigo' },

  // Communication
  { id: 'mail-1', name: 'Mail', icon: FiMail, category: 'Communication', tags: ['mail', 'email', 'message'], color: 'blue' },
  { id: 'phone-1', name: 'Phone', icon: FiPhone, category: 'Communication', tags: ['phone', 'call', 'communication'], color: 'green' },
  { id: 'monitor-1', name: 'Monitor', icon: FiMonitor, category: 'Communication', tags: ['monitor', 'screen', 'display'], color: 'slate' },

  // Files & Documents
  { id: 'folder-1', name: 'Folder', icon: FiFolder, category: 'Files', tags: ['folder', 'directory', 'files'], color: 'yellow' },
  { id: 'file-1', name: 'File', icon: FiFile, category: 'Files', tags: ['file', 'document', 'data'], color: 'gray' },
  { id: 'document-1', name: 'Document', icon: HiOutlineDocumentText, category: 'Files', tags: ['document', 'file', 'text'], color: 'blue' },
  { id: 'file-earmark-1', name: 'File Document', icon: BsFileEarmark, category: 'Files', tags: ['file', 'document', 'text'], color: 'indigo' },
  { id: 'image-1', name: 'Image', icon: FiImage, category: 'Files', tags: ['image', 'photo', 'picture'], color: 'purple' },
  { id: 'clipboard-1', name: 'Clipboard', icon: BsClipboard, category: 'Files', tags: ['clipboard', 'copy', 'paste'], color: 'slate' },

  // Other
  { id: 'zap-1', name: 'Zap', icon: FiZap, category: 'Other', tags: ['zap', 'fast', 'lightning'], color: 'yellow' },
  { id: 'zap-2', name: 'Lightning', icon: FiZap, category: 'Other', tags: ['bolt', 'lightning', 'fast'], color: 'amber' },
  { id: 'star-1', name: 'Star', icon: FiStar, category: 'Other', tags: ['star', 'favorite', 'rating'], color: 'yellow' },
  { id: 'heart-1', name: 'Heart', icon: FiHeart, category: 'Other', tags: ['heart', 'like', 'favorite'], color: 'rose' },
  { id: 'sun-1', name: 'Sun', icon: FiSun, category: 'Other', tags: ['sun', 'light', 'day'], color: 'amber' },
  { id: 'moon-1', name: 'Moon', icon: FiMoon, category: 'Other', tags: ['moon', 'dark', 'night'], color: 'indigo' },
  { id: 'anchor-1', name: 'Anchor', icon: FiAnchor, category: 'Other', tags: ['anchor', 'link', 'fixed'], color: 'blue' },
  { id: 'truck-1', name: 'Truck', icon: FiTruck, category: 'Other', tags: ['truck', 'delivery', 'shipping'], color: 'green' },
  { id: 'calendar-1', name: 'Calendar', icon: BsCalendar, category: 'Other', tags: ['calendar', 'date', 'schedule'], color: 'red' },
  { id: 'radio-1', name: 'Radio', icon: FiRadio, category: 'Other', tags: ['radio', 'broadcast', 'signal'], color: 'purple' },
  { id: 'memory-1', name: 'Memory', icon: MdMemory, category: 'Other', tags: ['memory', 'ram', 'chip'], color: 'cyan' },
  { id: 'integration-1', name: 'Integration', icon: MdIntegrationInstructions, category: 'Other', tags: ['integration', 'connect', 'api'], color: 'purple' },
];

interface IconPickerProps {
  onIconSelect: (icon: IconData) => void;
  onDragStart?: (e: React.DragEvent<HTMLDivElement>, icon: IconData) => void;
}

const IconPicker: React.FC<IconPickerProps> = ({ onIconSelect, onDragStart }) => {
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedCategory, setSelectedCategory] = useState<string>('All');

  // Get unique categories
  const categories = useMemo(() => {
    const cats = ['All', ...new Set(iconLibrary.map(icon => icon.category))];
    return cats.sort();
  }, []);

  // Filter icons based on search and category
  const filteredIcons = useMemo(() => {
    return iconLibrary.filter(icon => {
      const matchesSearch = searchQuery === '' ||
        icon.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
        icon.tags.some(tag => tag.toLowerCase().includes(searchQuery.toLowerCase()));

      const matchesCategory = selectedCategory === 'All' || icon.category === selectedCategory;

      return matchesSearch && matchesCategory;
    });
  }, [searchQuery, selectedCategory]);

  // Color mapping
  const colorMap: Record<string, { bg: string; border: string; text: string }> = {
    orange: { bg: 'bg-orange-50', border: 'border-orange-500', text: 'text-orange-600' },
    blue: { bg: 'bg-blue-50', border: 'border-blue-500', text: 'text-blue-600' },
    green: { bg: 'bg-green-50', border: 'border-green-500', text: 'text-green-600' },
    red: { bg: 'bg-red-50', border: 'border-red-500', text: 'text-red-600' },
    yellow: { bg: 'bg-yellow-50', border: 'border-yellow-500', text: 'text-yellow-600' },
    purple: { bg: 'bg-purple-50', border: 'border-purple-500', text: 'text-purple-600' },
    pink: { bg: 'bg-pink-50', border: 'border-pink-500', text: 'text-pink-600' },
    indigo: { bg: 'bg-indigo-50', border: 'border-indigo-500', text: 'text-indigo-600' },
    gray: { bg: 'bg-gray-50', border: 'border-gray-500', text: 'text-gray-600' },
    slate: { bg: 'bg-slate-50', border: 'border-slate-500', text: 'text-slate-600' },
    cyan: { bg: 'bg-cyan-50', border: 'border-cyan-500', text: 'text-cyan-600' },
    teal: { bg: 'bg-teal-50', border: 'border-teal-500', text: 'text-teal-600' },
    lime: { bg: 'bg-lime-50', border: 'border-lime-500', text: 'text-lime-600' },
    emerald: { bg: 'bg-emerald-50', border: 'border-emerald-500', text: 'text-emerald-600' },
    amber: { bg: 'bg-amber-50', border: 'border-amber-500', text: 'text-amber-600' },
    rose: { bg: 'bg-rose-50', border: 'border-rose-500', text: 'text-rose-600' },
    fuchsia: { bg: 'bg-fuchsia-50', border: 'border-fuchsia-500', text: 'text-fuchsia-600' },
    violet: { bg: 'bg-violet-50', border: 'border-violet-500', text: 'text-violet-600' },
    sky: { bg: 'bg-sky-50', border: 'border-sky-500', text: 'text-sky-600' },
  };

  return (
    <div className="flex flex-col h-full max-h-full bg-white overflow-hidden">
      {/* Header - Fixed */}
      <div className="shrink-0 p-3 border-b border-gray-200">
        {/* Search */}
        <div className="relative mb-2">
          <input
            type="text"
            placeholder="Search icons..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full px-3 py-2 pl-9 text-sm border border-gray-300 rounded-lg focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500"
          />
          <i className="fas fa-search absolute left-3 top-1/2 transform -translate-y-1/2 text-gray-400 text-xs" />
        </div>

        {/* Category Filter - Horizontal scroll */}
        <div className="flex gap-1.5 overflow-x-auto pb-1 scrollbar-thin">
          {categories.map(category => (
            <button
              key={category}
              onClick={() => setSelectedCategory(category)}
              className={`px-2 py-1 text-xs font-medium rounded-full transition-all whitespace-nowrap shrink-0 ${selectedCategory === category
                  ? 'bg-indigo-100 text-indigo-700 border border-indigo-300'
                  : 'bg-gray-100 text-gray-600 hover:bg-gray-200 border border-gray-200'
                }`}
            >
              {category}
            </button>
          ))}
        </div>
      </div>

      {/* Icons Grid - Scrollable */}
      <div className="flex-1 min-h-0 overflow-y-auto p-3">
        <div className="grid grid-cols-2 gap-2">
          {filteredIcons.map((icon) => {
            const IconComponent = icon.icon;
            const colors = colorMap[icon.color || 'gray'];

            return (
              <div
                key={icon.id}
                draggable
                onDragStart={(e) => onDragStart?.(e, icon)}
                onClick={() => onIconSelect(icon)}
                className={`
                  ${colors.bg} border-2 ${colors.border} rounded-lg p-3
                  cursor-grab active:cursor-grabbing
                  hover:shadow-md hover:scale-105 transition-all
                  flex flex-col items-center justify-center text-center
                `}
              >
                <div className={`w-10 h-10 rounded-lg flex items-center justify-center mb-2 ${colors.text}`}>
                  <IconComponent size={24} />
                </div>
                <span className="text-[10px] font-semibold text-gray-700 leading-tight">
                  {icon.name}
                </span>
              </div>
            );
          })}
        </div>

        {filteredIcons.length === 0 && (
          <div className="text-center py-8">
            <i className="fas fa-search text-gray-300 text-3xl mb-2" />
            <p className="text-sm text-gray-500">No icons found</p>
            <p className="text-xs text-gray-400 mt-1">Try a different search term</p>
          </div>
        )}
      </div>

      {/* Footer Stats - Fixed */}
      <div className="shrink-0 px-3 py-2 border-t border-gray-200 bg-gray-50">
        <div className="flex items-center justify-between text-xs text-gray-500">
          <span>{filteredIcons.length} icons</span>
          <span className="text-gray-400">of {iconLibrary.length}</span>
        </div>
      </div>
    </div>
  );
};

export default IconPicker;
export { iconLibrary };
