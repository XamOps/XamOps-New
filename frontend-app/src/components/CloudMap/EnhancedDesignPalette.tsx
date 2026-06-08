/**
 * EnhancedDesignPalette
 *
 * draw.io-style left sidebar:
 *  - Search bar at top (searches across ALL sections)
 *  - Collapsible sections: Shapes, AWS (by category), Icons (by category)
 *  - Click to add at canvas center · Drag to place at position
 */

import React, { useState, useMemo, useRef, useCallback } from 'react';
import { NODE_TYPE_DATA, TAILWIND_COLORS, AWS_ICONS } from '../../types/cloudmap';
import { iconLibrary } from './IconPicker';
import type { IconData } from './IconPicker';

// ─── Generic shape definitions ────────────────────────────────────────────────

export interface GenericShape {
  id: string;
  label: string;
  defaultLabel?: string;
  cytoscapeShape: string;
  fillColor: string;
  borderColor: string;
  tags: string[];
  isContainer?: boolean;
  containerType?: string;
}

const GENERIC_SHAPES: GenericShape[] = [
  // ── Rectangles ─────────────────────────────────────────────────────────────
  { id: 'rect',         label: 'Rectangle',  cytoscapeShape: 'rectangle',              fillColor: '#dbeafe', borderColor: '#3b82f6', tags: ['rect', 'box', 'square'] },
  { id: 'round',        label: 'Rounded',    cytoscapeShape: 'roundrectangle',         fillColor: '#f3e8ff', borderColor: '#a855f7', tags: ['round', 'rounded', 'box'] },
  { id: 'bottom-round', label: 'Tab',        cytoscapeShape: 'bottom-round-rectangle', fillColor: '#dbeafe', borderColor: '#3b82f6', tags: ['bottom', 'round', 'tab', 'rect'] },
  { id: 'cut-rect',     label: 'Process',    cytoscapeShape: 'cut-rectangle',          fillColor: '#fef9c3', borderColor: '#ca8a04', tags: ['process', 'step', 'flow', 'cut'] },
  // ── Ellipse ─────────────────────────────────────────────────────────────────
  { id: 'ellipse',      label: 'Ellipse',    cytoscapeShape: 'ellipse',                fillColor: '#dcfce7', borderColor: '#22c55e', tags: ['circle', 'oval', 'ellipse'] },
  // ── Triangles ───────────────────────────────────────────────────────────────
  { id: 'triangle',     label: 'Triangle',   cytoscapeShape: 'triangle',               fillColor: '#e0f2fe', borderColor: '#0ea5e9', tags: ['triangle', 'direction'] },
  { id: 'right-tri',    label: 'Right Tri',  cytoscapeShape: 'straight-triangle',      fillColor: '#e0f2fe', borderColor: '#0ea5e9', tags: ['right', 'triangle', 'straight'] },
  // ── Diamond ─────────────────────────────────────────────────────────────────
  { id: 'diamond',      label: 'Diamond',    cytoscapeShape: 'diamond',                fillColor: '#fef3c7', borderColor: '#f59e0b', tags: ['diamond', 'rhombus', 'decision'] },
  // ── Polygons ────────────────────────────────────────────────────────────────
  { id: 'pentagon',     label: 'Pentagon',   cytoscapeShape: 'pentagon',               fillColor: '#fce7f3', borderColor: '#ec4899', tags: ['pentagon', '5', 'five'] },
  { id: 'hexagon',      label: 'Hexagon',    cytoscapeShape: 'hexagon',                fillColor: '#ede9fe', borderColor: '#8b5cf6', tags: ['hex', 'hexagon', 'honeycomb'] },
  { id: 'concave-hex',  label: 'Arrow',      cytoscapeShape: 'concave-hexagon',        fillColor: '#e0f2fe', borderColor: '#0284c7', tags: ['concave', 'hexagon', 'arrow', 'chevron'] },
  { id: 'heptagon',     label: 'Heptagon',   cytoscapeShape: 'heptagon',               fillColor: '#d1fae5', borderColor: '#10b981', tags: ['heptagon', '7', 'seven'] },
  { id: 'octagon',      label: 'Octagon',    cytoscapeShape: 'octagon',                fillColor: '#fff7ed', borderColor: '#f97316', tags: ['octagon', '8', 'eight', 'stop'] },
  // ── Special ─────────────────────────────────────────────────────────────────
  { id: 'star',         label: 'Star',       cytoscapeShape: 'star',                   fillColor: '#fef9c3', borderColor: '#ca8a04', tags: ['star', 'highlight', 'important'] },
  { id: 'vee',          label: 'Vee',        cytoscapeShape: 'vee',                    fillColor: '#f1f5f9', borderColor: '#64748b', tags: ['vee', 'v', 'arrow', 'check', 'chevron'] },
  { id: 'barrel',       label: 'Cylinder',   cytoscapeShape: 'barrel',                 fillColor: '#ede9fe', borderColor: '#8b5cf6', tags: ['db', 'database', 'barrel', 'cylinder', 'storage'] },
  { id: 'tag',          label: 'Tag',        cytoscapeShape: 'tag',                    fillColor: '#d1fae5', borderColor: '#10b981', tags: ['tag', 'label', 'badge', 'arrow'] },
  { id: 'text',         label: 'Text',       cytoscapeShape: 'rectangle',              fillColor: 'transparent', borderColor: 'none', tags: ['text', 'label', 'note', 'annotation'] },
];

// ─── Flowchart shapes ─────────────────────────────────────────────────────────

const FLOWCHART_SHAPES: GenericShape[] = [
  { id: 'fl-start',    label: 'Start',       defaultLabel: 'Start',     cytoscapeShape: 'ellipse',       fillColor: '#dcfce7', borderColor: '#16a34a', tags: ['start', 'begin', 'flow', 'terminal'] },
  { id: 'fl-end',      label: 'End',         defaultLabel: 'End',       cytoscapeShape: 'ellipse',       fillColor: '#fee2e2', borderColor: '#dc2626', tags: ['end', 'stop', 'finish', 'flow', 'terminal'] },
  { id: 'fl-process',  label: 'Process',     defaultLabel: 'Process',   cytoscapeShape: 'rectangle',     fillColor: '#dbeafe', borderColor: '#2563eb', tags: ['process', 'task', 'step', 'flow', 'action'] },
  { id: 'fl-decision', label: 'Decision',    defaultLabel: 'Decision?', cytoscapeShape: 'diamond',       fillColor: '#fef9c3', borderColor: '#ca8a04', tags: ['decision', 'if', 'condition', 'branch', 'flow', 'gateway'] },
  { id: 'fl-doc',      label: 'Document',    defaultLabel: 'Document',  cytoscapeShape: 'cut-rectangle', fillColor: '#f0fdf4', borderColor: '#15803d', tags: ['document', 'doc', 'paper', 'report', 'flow'] },
  { id: 'fl-db',       label: 'Database',    defaultLabel: 'Database',  cytoscapeShape: 'barrel',        fillColor: '#ede9fe', borderColor: '#7c3aed', tags: ['database', 'db', 'storage', 'flow', 'data'] },
  { id: 'fl-io',       label: 'I/O',         defaultLabel: 'Input',     cytoscapeShape: 'tag',           fillColor: '#e0f2fe', borderColor: '#0284c7', tags: ['input', 'output', 'io', 'data', 'flow'] },
  { id: 'fl-prep',     label: 'Preparation', defaultLabel: 'Prepare',   cytoscapeShape: 'hexagon',       fillColor: '#fdf4ff', borderColor: '#a21caf', tags: ['prep', 'preparation', 'setup', 'initialize', 'flow'] },
];

// ─── Network shapes ───────────────────────────────────────────────────────────

const NETWORK_SHAPES: GenericShape[] = [
  { id: 'net-cloud',  label: 'Internet',     defaultLabel: 'Internet',      cytoscapeShape: 'ellipse',        fillColor: '#f0f9ff', borderColor: '#7dd3fc', tags: ['cloud', 'internet', 'web', 'network', 'cdn'] },
  { id: 'net-server', label: 'Server',       defaultLabel: 'Server',        cytoscapeShape: 'roundrectangle', fillColor: '#f1f5f9', borderColor: '#64748b', tags: ['server', 'host', 'machine', 'compute', 'network'] },
  { id: 'net-fw',     label: 'Firewall',     defaultLabel: 'Firewall',      cytoscapeShape: 'rectangle',      fillColor: '#fef2f2', borderColor: '#dc2626', tags: ['firewall', 'security', 'wall', 'filter', 'network'] },
  { id: 'net-switch', label: 'Switch',       defaultLabel: 'Switch',        cytoscapeShape: 'cut-rectangle',  fillColor: '#ecfdf5', borderColor: '#10b981', tags: ['switch', 'hub', 'network', 'lan'] },
  { id: 'net-lb',     label: 'Load Balancer',defaultLabel: 'Load Balancer', cytoscapeShape: 'diamond',        fillColor: '#f0fdf4', borderColor: '#22c55e', tags: ['load', 'balancer', 'lb', 'traffic', 'network'] },
  { id: 'net-db',     label: 'Storage',      defaultLabel: 'Storage',       cytoscapeShape: 'barrel',         fillColor: '#faf5ff', borderColor: '#a855f7', tags: ['storage', 'disk', 'volume', 'nas', 'network'] },
  { id: 'net-client', label: 'Client',       defaultLabel: 'Client',        cytoscapeShape: 'tag',            fillColor: '#eff6ff', borderColor: '#3b82f6', tags: ['client', 'browser', 'user', 'device', 'network'] },
  { id: 'net-subnet', label: 'Subnet',       defaultLabel: 'Subnet',        cytoscapeShape: 'rectangle',      fillColor: '#f8fafc', borderColor: '#94a3b8', tags: ['subnet', 'network', 'vpc', 'zone', 'segment'] },
];

// ─── Container shapes ─────────────────────────────────────────────────────────

const CONTAINER_SHAPES: GenericShape[] = [
  { id: 'c-vpc',    label: 'VPC',            defaultLabel: 'VPC',            cytoscapeShape: 'roundrectangle', fillColor: '#eef2ff', borderColor: '#6366f1', isContainer: true, containerType: 'vpc',    tags: ['vpc', 'virtual', 'private', 'cloud', 'network', 'container', 'aws'] },
  { id: 'c-subnet', label: 'Subnet',         defaultLabel: 'Subnet',         cytoscapeShape: 'rectangle',      fillColor: '#e0f2fe', borderColor: '#0ea5e9', isContainer: true, containerType: 'subnet', tags: ['subnet', 'network', 'segment', 'container', 'aws'] },
  { id: 'c-az',     label: 'Avail. Zone',    defaultLabel: 'AZ-1a',          cytoscapeShape: 'roundrectangle', fillColor: '#fff7ed', borderColor: '#f97316', isContainer: true, containerType: 'az',     tags: ['availability', 'zone', 'az', 'region', 'container', 'aws'] },
  { id: 'c-sg',     label: 'Security Group', defaultLabel: 'Security Group', cytoscapeShape: 'rectangle',      fillColor: '#fef2f2', borderColor: '#ef4444', isContainer: true, containerType: 'sg',     tags: ['security', 'group', 'sg', 'firewall', 'container', 'aws'] },
  { id: 'c-region', label: 'Region',         defaultLabel: 'us-east-1',      cytoscapeShape: 'roundrectangle', fillColor: '#faf5ff', borderColor: '#8b5cf6', isContainer: true, containerType: 'region', tags: ['region', 'aws', 'container', 'geography'] },
];

// ─── SVG shape preview ────────────────────────────────────────────────────────

const ShapePreview: React.FC<{ shape: GenericShape; size?: number }> = ({ shape, size = 28 }) => {
  const fill   = shape.fillColor   === 'transparent' ? 'none' : shape.fillColor;
  const stroke = shape.borderColor === 'none'        ? '#94a3b8' : shape.borderColor;
  const sw = 1.5;

  const paths: Record<string, React.ReactNode> = {
    // Rectangles
    'rectangle':               <rect x="2" y="5" width="36" height="20" fill={fill} stroke={stroke} strokeWidth={sw} />,
    'roundrectangle':          <rect x="2" y="5" width="36" height="20" rx="5" fill={fill} stroke={stroke} strokeWidth={sw} />,
    'bottom-round-rectangle':  <path d="M2,5 H38 V20 Q38,27 20,27 Q2,27 2,20 Z" fill={fill} stroke={stroke} strokeWidth={sw} />,
    'cut-rectangle':           <polygon points="8,5 38,5 38,25 2,25 2,11" fill={fill} stroke={stroke} strokeWidth={sw} />,
    // Ellipse
    'ellipse':                 <ellipse cx="20" cy="15" rx="18" ry="10" fill={fill} stroke={stroke} strokeWidth={sw} />,
    // Triangles
    'triangle':                <polygon points="20,3 38,27 2,27" fill={fill} stroke={stroke} strokeWidth={sw} />,
    'round-triangle':          <polygon points="20,4 37,26 3,26" fill={fill} stroke={stroke} strokeWidth={sw} />,
    'straight-triangle':       <polygon points="2,27 38,27 2,3" fill={fill} stroke={stroke} strokeWidth={sw} />,
    // Diamonds
    'diamond':                 <polygon points="20,4 36,15 20,26 4,15" fill={fill} stroke={stroke} strokeWidth={sw} />,
    'round-diamond':           <polygon points="20,4 36,15 20,26 4,15" fill={fill} stroke={stroke} strokeWidth={sw} />,
    // Pentagons
    'pentagon':                <polygon points="20,2 32.4,11 27.6,25.5 12.4,25.5 7.6,11" fill={fill} stroke={stroke} strokeWidth={sw} />,
    'round-pentagon':          <polygon points="20,2 32.4,11 27.6,25.5 12.4,25.5 7.6,11" fill={fill} stroke={stroke} strokeWidth={sw} />,
    // Hexagons
    'hexagon':                 <polygon points="20,3 35,10 35,20 20,27 5,20 5,10" fill={fill} stroke={stroke} strokeWidth={sw} />,
    'round-hexagon':           <polygon points="20,3 35,10 35,20 20,27 5,20 5,10" fill={fill} stroke={stroke} strokeWidth={sw} />,
    'concave-hexagon':         <polygon points="2,5 22,5 38,15 22,25 2,25 18,15" fill={fill} stroke={stroke} strokeWidth={sw} />,
    // Heptagons
    'heptagon':                <polygon points="20,2 30.2,6.9 32.7,17.9 25.6,26 14.4,26 7.3,17.9 9.8,6.9" fill={fill} stroke={stroke} strokeWidth={sw} />,
    'round-heptagon':          <polygon points="20,2 30.2,6.9 32.7,17.9 25.6,26 14.4,26 7.3,17.9 9.8,6.9" fill={fill} stroke={stroke} strokeWidth={sw} />,
    // Octagons
    'octagon':                 <polygon points="20,2 29.2,5.8 33,15 29.2,24.2 20,28 10.8,24.2 7,15 10.8,5.8" fill={fill} stroke={stroke} strokeWidth={sw} />,
    'round-octagon':           <polygon points="20,2 29.2,5.8 33,15 29.2,24.2 20,28 10.8,24.2 7,15 10.8,5.8" fill={fill} stroke={stroke} strokeWidth={sw} />,
    // Special
    'star':                    <polygon points="20,2 23.2,10.6 32.4,11 25.2,16.7 27.6,25.5 20,20.5 12.4,25.5 14.8,16.7 7.6,11 16.8,10.6" fill={fill} stroke={stroke} strokeWidth={sw} />,
    'vee':                     <polygon points="2,4 20,24 38,4 34,4 20,18 6,4" fill={fill} stroke={stroke} strokeWidth={sw} />,
    'barrel':                  <><rect x="4" y="8" width="32" height="15" fill={fill} stroke={stroke} strokeWidth={sw} /><ellipse cx="20" cy="8" rx="16" ry="4" fill={fill} stroke={stroke} strokeWidth={sw} /><ellipse cx="20" cy="23" rx="16" ry="4" fill={fill} stroke={stroke} strokeWidth={sw} /></>,
    'tag':                     <polygon points="4,5 33,5 38,15 33,25 4,25" fill={fill} stroke={stroke} strokeWidth={sw} />,
    'round-tag':               <polygon points="4,5 33,5 38,15 33,25 4,25" fill={fill} stroke={stroke} strokeWidth={sw} />,
  };

  return (
    <svg viewBox="0 0 40 30" width={size} height={size * 0.75} className="shrink-0">
      {paths[shape.cytoscapeShape] ?? <rect x="2" y="5" width="36" height="20" fill={fill} stroke={stroke} strokeWidth={sw} />}
      {shape.id === 'text' && (
        <text x="20" y="18" textAnchor="middle" fontSize="8" fill="#374151" fontWeight="600">Text</text>
      )}
    </svg>
  );
};

// ─── Unified palette item type ────────────────────────────────────────────────

type PaletteItemType = 'aws' | 'shape' | 'icon';

interface PaletteItem {
  id: string;
  label: string;
  section: string;
  tags: string[];
  kind: PaletteItemType;
  // payload
  awsType?:   string;
  shapeData?: GenericShape;
  iconData?:  IconData;
  // visual
  awsIconPath?: string;
  faIcon?:      string;
  color?:       string;
}

// ─── Build unified item list ───────────────────────────────────────────────────

const buildItems = (): PaletteItem[] => {
  const items: PaletteItem[] = [];

  const addShapes = (shapes: GenericShape[], section: string) => {
    shapes.forEach(shape => items.push({
      id: `shape-${shape.id}`,
      label: shape.label,
      section,
      tags: shape.tags,
      kind: 'shape',
      shapeData: shape,
    }));
  };

  addShapes(GENERIC_SHAPES,    'General');
  addShapes(FLOWCHART_SHAPES,  'Flowchart');
  addShapes(NETWORK_SHAPES,    'Network');
  addShapes(CONTAINER_SHAPES,  'Containers');

  // AWS services — appear in their sections when the AWS library is enabled
  Object.entries(NODE_TYPE_DATA).forEach(([type, data]) => {
    items.push({
      id: `aws-${type}`,
      label: type,
      section: `AWS — ${data.category}`,
      tags: [type.toLowerCase(), data.category.toLowerCase(), data.icon, 'aws', 'cloud'],
      kind: 'aws',
      awsType: type,
      awsIconPath: AWS_ICONS[type],
      faIcon: data.icon,
      color: data.color,
    });
  });

  // Icons — appear in their sections when the Icons library is enabled
  iconLibrary.forEach(icon => {
    items.push({
      id: `icon-${icon.id}`,
      label: icon.name,
      section: `Icons — ${icon.category}`,
      tags: [...icon.tags, icon.category.toLowerCase()],
      kind: 'icon',
      iconData: icon,
      color: icon.color,
    });
  });

  return items;
};

const ALL_ITEMS = buildItems();

// ─── Available optional libraries ─────────────────────────────────────────────

const AWS_SECTIONS = [
  'AWS — Compute', 'AWS — Network', 'AWS — Storage', 'AWS — Database',
  'AWS — Security', 'AWS — Messaging', 'AWS — Analytics', 'AWS — DevOps',
  'AWS — AI/ML', 'AWS — Management', 'AWS — Application',
];

const ICON_SECTIONS = Array.from(
  new Set(iconLibrary.map(i => `Icons — ${i.category}`))
).sort();

interface LibraryDef {
  id: string;
  label: string;
  description: string;
  faIcon: string;
  count: number;
}

const AVAILABLE_LIBRARIES: LibraryDef[] = [
  {
    id: 'aws',
    label: 'AWS Cloud Services',
    description: 'Amazon Web Services icons across all categories',
    faIcon: 'fa-aws',
    count: Object.keys(NODE_TYPE_DATA).length,
  },
  {
    id: 'icons',
    label: 'Icon Library',
    description: 'General-purpose icons (analytics, communication, UI…)',
    faIcon: 'fa-icons',
    count: iconLibrary.length,
  },
];

// ─── Dynamic section order based on enabled libraries ─────────────────────────

const getSectionOrder = (enabled: Set<string>): string[] => {
  const order = ['General', 'Flowchart', 'Network', 'Containers'];
  if (enabled.has('aws'))   order.push(...AWS_SECTIONS);
  if (enabled.has('icons')) order.push(...ICON_SECTIONS);
  return order;
};

// ─── Colour helper ────────────────────────────────────────────────────────────

const colorToHex = (color: string | undefined) =>
  TAILWIND_COLORS[color as keyof typeof TAILWIND_COLORS]?.[500] || '#6b7280';


// ─── Single palette item tile (draw.io style: icon-only, no label, tooltip) ──

interface TileProps {
  item: PaletteItem;
  onDragStart: (e: React.DragEvent<HTMLDivElement>, item: PaletteItem) => void;
  onClick: (item: PaletteItem) => void;
}

const PaletteTile: React.FC<TileProps> = ({ item, onDragStart, onClick }) => {
  const hex         = colorToHex(item.color);
  const isContainer = item.shapeData?.isContainer;

  const inner = () => {
    if (item.kind === 'shape' && item.shapeData) {
      return <ShapePreview shape={item.shapeData} size={30} />;
    }
    if (item.kind === 'aws') {
      if (item.awsIconPath) {
        return <img src={item.awsIconPath} alt={item.label} className="w-6 h-6 object-contain" />;
      }
      return <i className={`fas fa-${item.faIcon} text-base`} style={{ color: hex }} />;
    }
    if (item.kind === 'icon' && item.iconData) {
      const Icon = item.iconData.icon;
      return <span style={{ color: hex }}><Icon size={18} /></span>;
    }
    return null;
  };

  return (
    <div
      draggable
      onDragStart={(e) => onDragStart(e, item)}
      onClick={() => onClick(item)}
      title={item.label}
      className={`flex items-center justify-center w-full aspect-square rounded transition-colors select-none cursor-grab active:cursor-grabbing${
        isContainer
          ? ' border border-dashed border-current opacity-80 hover:bg-indigo-50'
          : ' hover:bg-[#e8eaf0]'
      }`}
      style={{ minHeight: 36, color: isContainer ? item.shapeData?.borderColor : undefined }}
    >
      {inner()}
    </div>
  );
};

// ─── Section header (draw.io style: just arrow + title, no icon, no count) ───

interface SectionHeaderProps {
  title: string;
  isOpen: boolean;
  onToggle: () => void;
}

const SectionHeader: React.FC<SectionHeaderProps> = ({ title, isOpen, onToggle }) => (
  <button
    onClick={onToggle}
    className="w-full flex items-center gap-1.5 px-2 py-1.5 hover:bg-gray-100 transition-colors text-left"
  >
    <i className={`fas fa-chevron-${isOpen ? 'down' : 'right'} text-[9px] text-gray-500 w-2.5 shrink-0`} />
    <span className="text-[11px] font-semibold text-gray-700 truncate">{title}</span>
  </button>
);

// ─── Props ────────────────────────────────────────────────────────────────────

interface Props {
  mode: 'live' | 'design';
  onDragStart: (e: React.DragEvent<HTMLDivElement>, type: string) => void;
  onShapeDragStart: (e: React.DragEvent<HTMLDivElement>, shape: GenericShape) => void;
  onIconSelect?: (icon: IconData) => void;
  onShapeSelect?: (shape: GenericShape) => void;
  onAwsTypeSelect?: (type: string) => void;
}

// ─── More Shapes Modal ────────────────────────────────────────────────────────

interface MoreShapesModalProps {
  enabledLibraries: Set<string>;
  onApply: (next: Set<string>) => void;
  onClose: () => void;
}

const MoreShapesModal: React.FC<MoreShapesModalProps> = ({ enabledLibraries, onApply, onClose }) => {
  const [draft, setDraft] = useState<Set<string>>(new Set(enabledLibraries));

  const toggle = (id: string) => setDraft(prev => {
    const next = new Set(prev);
    next.has(id) ? next.delete(id) : next.add(id);
    return next;
  });

  return (
    <div className="fixed inset-0 z-[400] flex items-center justify-center">
      <div className="absolute inset-0 bg-black/40 backdrop-blur-sm" onClick={onClose} />
      <div className="relative bg-white rounded-2xl shadow-2xl w-80 mx-4 overflow-hidden">
        {/* Header */}
        <div className="px-4 py-3 border-b border-gray-100 flex items-center gap-2.5">
          <div className="w-8 h-8 bg-indigo-100 rounded-xl flex items-center justify-center shrink-0">
            <i className="fas fa-shapes text-indigo-600 text-xs" />
          </div>
          <div className="flex-1">
            <h2 className="text-sm font-bold text-gray-900">More Shapes</h2>
            <p className="text-[10px] text-gray-500">Enable additional shape libraries</p>
          </div>
          <button onClick={onClose} className="w-6 h-6 flex items-center justify-center rounded-lg hover:bg-gray-100 text-gray-400">
            <i className="fas fa-times text-[10px]" />
          </button>
        </div>

        {/* Library list */}
        <div className="p-3 space-y-2">
          {AVAILABLE_LIBRARIES.map(lib => (
            <label
              key={lib.id}
              className="flex items-start gap-3 p-3 rounded-xl border border-gray-100 hover:border-indigo-200 hover:bg-indigo-50/30 cursor-pointer transition-colors"
            >
              <input
                type="checkbox"
                checked={draft.has(lib.id)}
                onChange={() => toggle(lib.id)}
                className="mt-0.5 accent-indigo-600"
              />
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-1.5 mb-0.5">
                  <i className={`fab ${lib.faIcon} text-[11px] text-gray-500`} />
                  <span className="text-xs font-semibold text-gray-800">{lib.label}</span>
                  <span className="ml-auto text-[9px] text-gray-400 font-medium">{lib.count} shapes</span>
                </div>
                <p className="text-[10px] text-gray-500 leading-tight">{lib.description}</p>
              </div>
            </label>
          ))}
        </div>

        {/* Footer */}
        <div className="px-3 pb-3 flex gap-2">
          <button
            onClick={onClose}
            className="flex-1 px-3 py-2 border border-gray-200 text-gray-700 text-xs font-semibold rounded-xl hover:bg-gray-50 transition-colors"
          >
            Cancel
          </button>
          <button
            onClick={() => { onApply(draft); onClose(); }}
            className="flex-1 px-3 py-2 bg-indigo-600 text-white text-xs font-semibold rounded-xl hover:bg-indigo-700 transition-colors"
          >
            Apply
          </button>
        </div>
      </div>
    </div>
  );
};

// ─── Main component ───────────────────────────────────────────────────────────

const EnhancedDesignPalette: React.FC<Props> = ({
  mode, onDragStart, onShapeDragStart, onIconSelect, onShapeSelect, onAwsTypeSelect,
}) => {
  // ── ALL hooks must come before any conditional return ──────────────────────
  const [query,            setQuery]            = useState('');
  const [openSections,     setOpenSections]     = useState<Set<string>>(
    () => new Set(['General', 'Flowchart', 'Network', 'Containers'])
  );
  const [enabledLibraries, setEnabledLibraries] = useState<Set<string>>(() => {
    try {
      const saved = localStorage.getItem('cloudmap-libraries');
      return saved ? new Set(JSON.parse(saved) as string[]) : new Set<string>();
    } catch { return new Set<string>(); }
  });
  const [showMoreShapes, setShowMoreShapes] = useState(false);
  const searchRef = useRef<HTMLInputElement>(null);

  const sectionOrder = useMemo(() => getSectionOrder(enabledLibraries), [enabledLibraries]);

  const toggleSection = useCallback((title: string) => {
    setOpenSections(prev => {
      const next = new Set(prev);
      next.has(title) ? next.delete(title) : next.add(title);
      return next;
    });
  }, []);

  const applyLibraries = useCallback((next: Set<string>) => {
    setEnabledLibraries(next);
    try { localStorage.setItem('cloudmap-libraries', JSON.stringify([...next])); } catch { /* ignore */ }
  }, []);

  const filteredItems = useMemo(() => {
    if (!query.trim()) return ALL_ITEMS;
    const q = query.toLowerCase();
    return ALL_ITEMS.filter(item =>
      item.label.toLowerCase().includes(q) ||
      item.tags.some(t => t.includes(q)) ||
      item.section.toLowerCase().includes(q)
    );
  }, [query]);

  const groupedItems = useMemo(() => {
    const map = new Map<string, PaletteItem[]>();
    sectionOrder.forEach((s: string) => map.set(s, []));
    filteredItems.forEach(item => {
      if (!map.has(item.section)) map.set(item.section, []);
      map.get(item.section)!.push(item);
    });
    return map;
  }, [filteredItems, sectionOrder]);

  const handleDragStart = useCallback((e: React.DragEvent<HTMLDivElement>, item: PaletteItem) => {
    if (item.kind === 'aws' && item.awsType) {
      onDragStart(e, item.awsType);
    } else if (item.kind === 'shape' && item.shapeData) {
      onShapeDragStart(e, item.shapeData);
    } else if (item.kind === 'icon' && item.iconData) {
      e.dataTransfer.setData('iconData', JSON.stringify(item.iconData));
      e.dataTransfer.setData('type', 'custom-icon');
    }
  }, [onDragStart, onShapeDragStart]);

  const handleClick = useCallback((item: PaletteItem) => {
    if (item.kind === 'icon' && item.iconData)   onIconSelect?.(item.iconData);
    if (item.kind === 'shape' && item.shapeData) onShapeSelect?.(item.shapeData);
    if (item.kind === 'aws' && item.awsType)     onAwsTypeSelect?.(item.awsType);
  }, [onIconSelect, onShapeSelect, onAwsTypeSelect]);

  // ── Guard: render nothing in live mode ────────────────────────────────────
  if (mode !== 'design') return null;

  const isSearching = query.trim().length > 0;

  return (
    <>
      {showMoreShapes && (
        <MoreShapesModal
          enabledLibraries={enabledLibraries}
          onApply={applyLibraries}
          onClose={() => setShowMoreShapes(false)}
        />
      )}

      <div className="w-[200px] bg-white border-r border-gray-200 flex flex-col h-full max-h-full shrink-0 z-10">

        {/* ── Search bar ── */}
        <div className="px-2 py-2 border-b border-gray-200 shrink-0">
          <div className="relative">
            <input
              ref={searchRef}
              type="text"
              value={query}
              onChange={e => setQuery(e.target.value)}
              placeholder="Search shapes, AWS services…"
              onKeyDown={e => { if (e.key === '/' && !query) { e.preventDefault(); searchRef.current?.focus(); } }}
              className="w-full px-2 pr-6 py-1 text-[11px] bg-white border border-gray-300 rounded focus:outline-none focus:border-indigo-400 placeholder-gray-400"
            />
            {query ? (
              <button onClick={() => setQuery('')} className="absolute right-1.5 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600">
                <i className="fas fa-times text-[9px]" />
              </button>
            ) : (
              <i className="fas fa-search absolute right-2 top-1/2 -translate-y-1/2 text-gray-400 text-[9px] pointer-events-none" />
            )}
          </div>
        </div>

        {/* ── Body ── */}
        <div className="flex-1 overflow-y-auto palette-scroll">

          {/* ── SEARCH RESULTS ── */}
          {isSearching && (
            <div className="p-2">
              {filteredItems.length === 0 ? (
                <div className="text-center py-8">
                  <i className="fas fa-search text-gray-300 text-2xl mb-2 block" />
                  <p className="text-xs text-gray-500">No results for "{query}"</p>
                </div>
              ) : (
                <>
                  <p className="text-[9px] text-gray-400 font-medium mb-2 px-1">
                    {filteredItems.length} result{filteredItems.length !== 1 ? 's' : ''}
                  </p>
                  <div className="grid grid-cols-4 gap-0.5">
                    {filteredItems.map(item => (
                      <PaletteTile key={item.id} item={item} onDragStart={handleDragStart} onClick={handleClick} />
                    ))}
                  </div>
                </>
              )}
            </div>
          )}

          {/* ── SECTIONS ── */}
          {!isSearching && sectionOrder.map((section: string) => {
            const items = groupedItems.get(section) || [];
            if (items.length === 0) return null;
            const isOpen = openSections.has(section);
            return (
              <div key={section} className="border-b border-gray-100 last:border-b-0">
                <SectionHeader title={section} isOpen={isOpen} onToggle={() => toggleSection(section)} />
                {isOpen && (
                  <div className="px-1.5 pb-2 pt-0.5">
                    <div className="grid grid-cols-4 gap-0.5">
                      {items.map(item => (
                        <PaletteTile key={item.id} item={item} onDragStart={handleDragStart} onClick={handleClick} />
                      ))}
                    </div>
                  </div>
                )}
              </div>
            );
          })}
        </div>

        {/* ── More Shapes button ── */}
        <div className="px-2 py-2 border-t border-gray-200 shrink-0">
          <button
            onClick={() => setShowMoreShapes(true)}
            className="w-full flex items-center justify-center gap-1.5 px-3 py-1.5 text-[11px] font-semibold text-indigo-600 border border-indigo-200 rounded-lg hover:bg-indigo-50 transition-colors"
          >
            <i className="fas fa-plus text-[9px]" />
            More Shapes
          </button>
        </div>
      </div>
    </>
  );
};

export default EnhancedDesignPalette;
