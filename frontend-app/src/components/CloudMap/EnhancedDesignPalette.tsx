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
  { id: 'hexagon',      label: 'Hexagon',    cytoscapeShape: 'hexagon',                fillColor: '#f3f4f6', borderColor: '#6b7280', tags: ['hex', 'hexagon', 'honeycomb'] },
  { id: 'concave-hex',  label: 'Arrow',      cytoscapeShape: 'concave-hexagon',        fillColor: '#e0f2fe', borderColor: '#0284c7', tags: ['concave', 'hexagon', 'arrow', 'chevron'] },
  { id: 'heptagon',     label: 'Heptagon',   cytoscapeShape: 'heptagon',               fillColor: '#d1fae5', borderColor: '#10b981', tags: ['heptagon', '7', 'seven'] },
  { id: 'octagon',      label: 'Octagon',    cytoscapeShape: 'octagon',                fillColor: '#fff7ed', borderColor: '#f97316', tags: ['octagon', '8', 'eight', 'stop'] },
  // ── Special ─────────────────────────────────────────────────────────────────
  { id: 'star',         label: 'Star',       cytoscapeShape: 'star',                   fillColor: '#fef9c3', borderColor: '#ca8a04', tags: ['star', 'highlight', 'important'] },
  { id: 'vee',          label: 'Vee',        cytoscapeShape: 'vee',                    fillColor: '#f1f5f9', borderColor: '#64748b', tags: ['vee', 'v', 'arrow', 'check', 'chevron'] },
  { id: 'barrel',       label: 'Cylinder',   cytoscapeShape: 'barrel',                 fillColor: '#f3f4f6', borderColor: '#6b7280', tags: ['db', 'database', 'barrel', 'cylinder', 'storage'] },
  { id: 'tag',          label: 'Tag',        cytoscapeShape: 'tag',                    fillColor: '#d1fae5', borderColor: '#10b981', tags: ['tag', 'label', 'badge', 'arrow'] },
  { id: 'text',         label: 'Text',       cytoscapeShape: 'rectangle',              fillColor: 'transparent', borderColor: 'none',     tags: ['text', 'label', 'note', 'annotation'] },
  { id: 'sticky-note',  label: 'Sticky Note', defaultLabel: 'Note...', cytoscapeShape: 'roundrectangle', fillColor: '#fef08a', borderColor: '#eab308', tags: ['note', 'sticky', 'annotation', 'memo', 'reminder'] },
];

// ─── Flowchart shapes ─────────────────────────────────────────────────────────

const FLOWCHART_SHAPES: GenericShape[] = [
  { id: 'fl-start',    label: 'Start',       defaultLabel: 'Start',     cytoscapeShape: 'ellipse',       fillColor: '#dcfce7', borderColor: '#16a34a', tags: ['start', 'begin', 'flow', 'terminal'] },
  { id: 'fl-end',      label: 'End',         defaultLabel: 'End',       cytoscapeShape: 'ellipse',       fillColor: '#fee2e2', borderColor: '#dc2626', tags: ['end', 'stop', 'finish', 'flow', 'terminal'] },
  { id: 'fl-process',  label: 'Process',     defaultLabel: 'Process',   cytoscapeShape: 'rectangle',     fillColor: '#dbeafe', borderColor: '#2563eb', tags: ['process', 'task', 'step', 'flow', 'action'] },
  { id: 'fl-decision', label: 'Decision',    defaultLabel: 'Decision?', cytoscapeShape: 'diamond',       fillColor: '#fef9c3', borderColor: '#ca8a04', tags: ['decision', 'if', 'condition', 'branch', 'flow', 'gateway'] },
  { id: 'fl-doc',      label: 'Document',    defaultLabel: 'Document',  cytoscapeShape: 'cut-rectangle', fillColor: '#f0fdf4', borderColor: '#15803d', tags: ['document', 'doc', 'paper', 'report', 'flow'] },
  { id: 'fl-db',       label: 'Database',    defaultLabel: 'Database',  cytoscapeShape: 'barrel',        fillColor: '#f3f4f6', borderColor: '#6b7280', tags: ['database', 'db', 'storage', 'flow', 'data'] },
  { id: 'fl-io',       label: 'I/O',         defaultLabel: 'Input',     cytoscapeShape: 'tag',           fillColor: '#e0f2fe', borderColor: '#0284c7', tags: ['input', 'output', 'io', 'data', 'flow'] },
  { id: 'fl-prep',     label: 'Preparation', defaultLabel: 'Prepare',   cytoscapeShape: 'hexagon',       fillColor: '#f3f4f6', borderColor: '#6b7280', tags: ['prep', 'preparation', 'setup', 'initialize', 'flow'] },
];

// ─── Network shapes ───────────────────────────────────────────────────────────

const NETWORK_SHAPES: GenericShape[] = [
  { id: 'net-cloud',  label: 'Internet',     defaultLabel: 'Internet',      cytoscapeShape: 'ellipse',        fillColor: '#f0f9ff', borderColor: '#7dd3fc', tags: ['cloud', 'internet', 'web', 'network', 'cdn'] },
  { id: 'net-server', label: 'Server',       defaultLabel: 'Server',        cytoscapeShape: 'roundrectangle', fillColor: '#f1f5f9', borderColor: '#64748b', tags: ['server', 'host', 'machine', 'compute', 'network'] },
  { id: 'net-fw',     label: 'Firewall',     defaultLabel: 'Firewall',      cytoscapeShape: 'rectangle',      fillColor: '#fef2f2', borderColor: '#dc2626', tags: ['firewall', 'security', 'wall', 'filter', 'network'] },
  { id: 'net-switch', label: 'Switch',       defaultLabel: 'Switch',        cytoscapeShape: 'cut-rectangle',  fillColor: '#ecfdf5', borderColor: '#10b981', tags: ['switch', 'hub', 'network', 'lan'] },
  { id: 'net-lb',     label: 'Load Balancer',defaultLabel: 'Load Balancer', cytoscapeShape: 'diamond',        fillColor: '#f0fdf4', borderColor: '#22c55e', tags: ['load', 'balancer', 'lb', 'traffic', 'network'] },
  { id: 'net-db',     label: 'Storage',      defaultLabel: 'Storage',       cytoscapeShape: 'barrel',         fillColor: '#f3f4f6', borderColor: '#6b7280', tags: ['storage', 'disk', 'volume', 'nas', 'network'] },
  { id: 'net-client', label: 'Client',       defaultLabel: 'Client',        cytoscapeShape: 'tag',            fillColor: '#eff6ff', borderColor: '#3b82f6', tags: ['client', 'browser', 'user', 'device', 'network'] },
  { id: 'net-subnet', label: 'Subnet',       defaultLabel: 'Subnet',        cytoscapeShape: 'rectangle',      fillColor: '#f8fafc', borderColor: '#94a3b8', tags: ['subnet', 'network', 'vpc', 'zone', 'segment'] },
];

// ─── Container shapes ─────────────────────────────────────────────────────────

const CONTAINER_SHAPES: GenericShape[] = [
  { id: 'c-vpc',    label: 'VPC',            defaultLabel: 'VPC',            cytoscapeShape: 'roundrectangle', fillColor: '#f9fafb', borderColor: '#374151', isContainer: true, containerType: 'vpc',    tags: ['vpc', 'virtual', 'private', 'cloud', 'network', 'container', 'aws'] },
  { id: 'c-subnet', label: 'Subnet',         defaultLabel: 'Subnet',         cytoscapeShape: 'rectangle',      fillColor: '#e0f2fe', borderColor: '#0ea5e9', isContainer: true, containerType: 'subnet', tags: ['subnet', 'network', 'segment', 'container', 'aws'] },
  { id: 'c-az',     label: 'Avail. Zone',    defaultLabel: 'AZ-1a',          cytoscapeShape: 'roundrectangle', fillColor: '#fff7ed', borderColor: '#f97316', isContainer: true, containerType: 'az',     tags: ['availability', 'zone', 'az', 'region', 'container', 'aws'] },
  { id: 'c-sg',     label: 'Security Group', defaultLabel: 'Security Group', cytoscapeShape: 'rectangle',      fillColor: '#fef2f2', borderColor: '#ef4444', isContainer: true, containerType: 'sg',     tags: ['security', 'group', 'sg', 'firewall', 'container', 'aws'] },
  { id: 'c-region',    label: 'Region',     defaultLabel: 'us-east-1',   cytoscapeShape: 'roundrectangle', fillColor: '#f9fafb', borderColor: '#9ca3af', isContainer: true, containerType: 'region',    tags: ['region', 'aws', 'container', 'geography'] },
  { id: 'c-swimlane',  label: 'Swimlane',   defaultLabel: 'Swimlane',    cytoscapeShape: 'roundrectangle', fillColor: '#f0f9ff', borderColor: '#7dd3fc', isContainer: true, containerType: 'swimlane', tags: ['swimlane', 'lane', 'pool', 'process', 'container'] },
];

// ─── Entity-Relationship shapes ───────────────────────────────────────────────

const ER_SHAPES: GenericShape[] = [
  { id: 'er-entity',       label: 'Entity',              defaultLabel: 'Entity',       cytoscapeShape: 'rectangle',      fillColor: '#ffffff', borderColor: '#374151', tags: ['er', 'entity', 'table', 'database'] },
  { id: 'er-weak-entity',  label: 'Weak Entity',         defaultLabel: 'Weak Entity',  cytoscapeShape: 'rectangle',      fillColor: '#ffffff', borderColor: '#374151', tags: ['er', 'weak', 'entity', 'database'] },
  { id: 'er-attribute',    label: 'Attribute',           defaultLabel: 'attr',         cytoscapeShape: 'ellipse',        fillColor: '#ffffff', borderColor: '#374151', tags: ['er', 'attribute', 'field', 'database'] },
  { id: 'er-key-attr',     label: 'Key Attribute',       defaultLabel: 'key',          cytoscapeShape: 'ellipse',        fillColor: '#f3f4f6', borderColor: '#374151', tags: ['er', 'key', 'primary', 'attribute', 'database'] },
  { id: 'er-relationship', label: 'Relationship',        defaultLabel: 'rel',          cytoscapeShape: 'diamond',        fillColor: '#f3f4f6', borderColor: '#374151', tags: ['er', 'relationship', 'join', 'database'] },
  { id: 'er-isa',          label: 'ISA / Generalization',defaultLabel: 'ISA',          cytoscapeShape: 'triangle',       fillColor: '#f3f4f6', borderColor: '#374151', tags: ['er', 'isa', 'generalization', 'inheritance', 'database'] },
];

// ─── UML shapes ───────────────────────────────────────────────────────────────

const UML_SHAPES: GenericShape[] = [
  { id: 'uml-class',     label: 'Class',        defaultLabel: 'ClassName',   cytoscapeShape: 'rectangle',      fillColor: '#ffffff', borderColor: '#374151', tags: ['uml', 'class', 'object', 'oop'] },
  { id: 'uml-interface', label: 'Interface',    defaultLabel: '«interface»', cytoscapeShape: 'ellipse',        fillColor: '#ffffff', borderColor: '#374151', tags: ['uml', 'interface', 'contract', 'oop'] },
  { id: 'uml-usecase',   label: 'Use Case',     defaultLabel: 'Use Case',    cytoscapeShape: 'ellipse',        fillColor: '#f0f9ff', borderColor: '#374151', tags: ['uml', 'usecase', 'actor', 'scenario'] },
  { id: 'uml-state',     label: 'State',        defaultLabel: 'State',       cytoscapeShape: 'roundrectangle', fillColor: '#f0f9ff', borderColor: '#374151', tags: ['uml', 'state', 'machine', 'transition'] },
  { id: 'uml-initial',   label: 'Initial State',defaultLabel: '',            cytoscapeShape: 'ellipse',        fillColor: '#111827', borderColor: '#111827', tags: ['uml', 'initial', 'start', 'state'] },
  { id: 'uml-final',     label: 'Final State',  defaultLabel: '',            cytoscapeShape: 'ellipse',        fillColor: '#111827', borderColor: '#374151', tags: ['uml', 'final', 'end', 'state'] },
  { id: 'uml-note',      label: 'Note',         defaultLabel: 'Note…',       cytoscapeShape: 'cut-rectangle',  fillColor: '#fef9c3', borderColor: '#d97706', tags: ['uml', 'note', 'comment', 'annotation'] },
  { id: 'uml-component', label: 'Component',    defaultLabel: '«component»', cytoscapeShape: 'rectangle',      fillColor: '#ffffff', borderColor: '#374151', tags: ['uml', 'component', 'module', 'package'] },
];

// ─── BPMN shapes ──────────────────────────────────────────────────────────────

const BPMN_SHAPES: GenericShape[] = [
  { id: 'bpmn-start',      label: 'Start Event',    defaultLabel: '',           cytoscapeShape: 'ellipse',        fillColor: '#dcfce7', borderColor: '#16a34a', tags: ['bpmn', 'start', 'event', 'process'] },
  { id: 'bpmn-end',        label: 'End Event',      defaultLabel: '',           cytoscapeShape: 'ellipse',        fillColor: '#fee2e2', borderColor: '#dc2626', tags: ['bpmn', 'end', 'event', 'process'] },
  { id: 'bpmn-task',       label: 'Task',           defaultLabel: 'Task',       cytoscapeShape: 'roundrectangle', fillColor: '#f0f9ff', borderColor: '#374151', tags: ['bpmn', 'task', 'activity', 'process'] },
  { id: 'bpmn-gateway',    label: 'Gateway (XOR)',  defaultLabel: '×',          cytoscapeShape: 'diamond',        fillColor: '#fef9c3', borderColor: '#d97706', tags: ['bpmn', 'gateway', 'xor', 'decision', 'process'] },
  { id: 'bpmn-and-gateway',label: 'Gateway (AND)',  defaultLabel: '+',          cytoscapeShape: 'diamond',        fillColor: '#fef9c3', borderColor: '#d97706', tags: ['bpmn', 'gateway', 'and', 'parallel', 'process'] },
  { id: 'bpmn-subprocess', label: 'Sub-Process',    defaultLabel: 'Sub-Process',cytoscapeShape: 'roundrectangle', fillColor: '#ffffff', borderColor: '#374151', tags: ['bpmn', 'subprocess', 'sub', 'process'] },
  { id: 'bpmn-pool',       label: 'Pool',           defaultLabel: 'Pool',       cytoscapeShape: 'rectangle',      fillColor: '#f8fafc', borderColor: '#6b7280', isContainer: true, containerType: 'group', tags: ['bpmn', 'pool', 'lane', 'participant', 'process'] },
  { id: 'bpmn-data',       label: 'Data Object',    defaultLabel: 'Data',       cytoscapeShape: 'tag',            fillColor: '#ffffff', borderColor: '#374151', tags: ['bpmn', 'data', 'object', 'artifact', 'process'] },
];

// ─── Wireframe shapes ─────────────────────────────────────────────────────────

const WIREFRAME_SHAPES: GenericShape[] = [
  { id: 'wf-button',   label: 'Button',           defaultLabel: 'Button',    cytoscapeShape: 'roundrectangle', fillColor: '#374151', borderColor: '#111827', tags: ['wireframe', 'button', 'cta', 'ui'] },
  { id: 'wf-input',    label: 'Input Field',      defaultLabel: 'Input…',    cytoscapeShape: 'rectangle',      fillColor: '#ffffff', borderColor: '#d1d5db', tags: ['wireframe', 'input', 'field', 'form', 'ui'] },
  { id: 'wf-dropdown', label: 'Dropdown',         defaultLabel: 'Select ▾',  cytoscapeShape: 'rectangle',      fillColor: '#ffffff', borderColor: '#d1d5db', tags: ['wireframe', 'dropdown', 'select', 'form', 'ui'] },
  { id: 'wf-checkbox', label: 'Checkbox',         defaultLabel: '✓ Option',  cytoscapeShape: 'rectangle',      fillColor: '#ffffff', borderColor: '#374151', tags: ['wireframe', 'checkbox', 'toggle', 'form', 'ui'] },
  { id: 'wf-card',     label: 'Card',             defaultLabel: 'Card Title',cytoscapeShape: 'roundrectangle', fillColor: '#ffffff', borderColor: '#e5e7eb', tags: ['wireframe', 'card', 'panel', 'container', 'ui'] },
  { id: 'wf-image',    label: 'Image Placeholder',defaultLabel: '🖼 Image',  cytoscapeShape: 'rectangle',      fillColor: '#f3f4f6', borderColor: '#d1d5db', tags: ['wireframe', 'image', 'photo', 'media', 'ui'] },
  { id: 'wf-nav',      label: 'Navigation Bar',   defaultLabel: '☰  Nav',    cytoscapeShape: 'rectangle',      fillColor: '#111827', borderColor: '#000000', tags: ['wireframe', 'nav', 'navbar', 'header', 'menu', 'ui'] },
  { id: 'wf-modal',    label: 'Modal Dialog',     defaultLabel: '⊠ Dialog',  cytoscapeShape: 'roundrectangle', fillColor: '#ffffff', borderColor: '#374151', tags: ['wireframe', 'modal', 'dialog', 'popup', 'ui'] },
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
      {shape.id === 'sticky-note' && (
        <polygon points="31,5 38,12 38,25 2,25 2,5" fill="#fef08a" stroke="#eab308" strokeWidth={sw} />
      )}
    </svg>
  );
};

// ─── GCP service definitions ──────────────────────────────────────────────────

interface CloudService { label: string; iconPath: string; category: string; tags: string[]; }

export const GCP_SERVICES: Record<string, CloudService> = {
  'gcp-compute-engine':  { label: 'Compute Engine',  iconPath: '/icons/gcp-compute-engine.png',  category: 'Compute',    tags: ['compute', 'vm', 'virtual', 'machine', 'instance', 'gcp'] },
  'gcp-cloud-run':       { label: 'Cloud Run',       iconPath: '/icons/gcp-cloud-run.png',       category: 'Compute',    tags: ['serverless', 'container', 'run', 'gcp'] },
  'gcp-cloud-functions': { label: 'Cloud Functions', iconPath: '/icons/gcp-cloud-functions.png', category: 'Compute',    tags: ['lambda', 'function', 'serverless', 'gcp'] },
  'gcp-gke':             { label: 'GKE',             iconPath: '/icons/gcp-gke.png',             category: 'Compute',    tags: ['kubernetes', 'k8s', 'container', 'cluster', 'gcp'] },
  'gcp-vpc':             { label: 'VPC',             iconPath: '/icons/gcp-vpc.png',             category: 'Network',    tags: ['vpc', 'network', 'virtual', 'private', 'cloud', 'gcp'] },
  'gcp-load-balancer':   { label: 'Load Balancer',   iconPath: '/icons/gcp-load-balancer.png',   category: 'Network',    tags: ['load', 'balancer', 'lb', 'traffic', 'gcp'] },
  'gcp-cloud-dns':       { label: 'Cloud DNS',       iconPath: '/icons/gcp-cloud-dns.png',       category: 'Network',    tags: ['dns', 'domain', 'routing', 'gcp'] },
  'gcp-nat':             { label: 'Cloud NAT',       iconPath: '/icons/gcp-nat.png',             category: 'Network',    tags: ['nat', 'gateway', 'network', 'gcp'] },
  'gcp-cloud-storage':   { label: 'Cloud Storage',   iconPath: '/icons/gcp-cloud-storage.png',   category: 'Storage',    tags: ['storage', 'bucket', 's3', 'object', 'gcp'] },
  'gcp-cloud-sql':       { label: 'Cloud SQL',       iconPath: '/icons/gcp-cloud-sql.png',       category: 'Database',   tags: ['sql', 'mysql', 'postgres', 'database', 'rds', 'gcp'] },
  'gcp-bigquery':        { label: 'BigQuery',        iconPath: '/icons/gcp-bigquery.png',        category: 'Analytics',  tags: ['bigquery', 'analytics', 'warehouse', 'query', 'gcp'] },
  'gcp-dataflow':        { label: 'Dataflow',        iconPath: '/icons/gcp-dataflow.png',        category: 'Analytics',  tags: ['dataflow', 'streaming', 'pipeline', 'gcp'] },
  'gcp-dataproc':        { label: 'Dataproc',        iconPath: '/icons/gcp-dataproc.png',        category: 'Analytics',  tags: ['dataproc', 'hadoop', 'spark', 'gcp'] },
  'gcp-dataplex':        { label: 'Dataplex',        iconPath: '/icons/gcp-dataplex.png',        category: 'Analytics',  tags: ['dataplex', 'data', 'lake', 'gcp'] },
  'gcp-pubsub':          { label: 'Pub/Sub',         iconPath: '/icons/gcp-pubsub.png',          category: 'Messaging',  tags: ['pubsub', 'messaging', 'queue', 'event', 'gcp'] },
  'gcp-cloud-armor':     { label: 'Cloud Armor',     iconPath: '/icons/gcp-cloud-armor.png',     category: 'Security',   tags: ['armor', 'waf', 'ddos', 'security', 'gcp'] },
  'gcp-iam':             { label: 'IAM',             iconPath: '/icons/gcp-iam.png',             category: 'Security',   tags: ['iam', 'identity', 'access', 'permission', 'gcp'] },
  'gcp-kms':             { label: 'KMS',             iconPath: '/icons/gcp-kms.png',             category: 'Security',   tags: ['kms', 'key', 'encryption', 'secret', 'gcp'] },
  'gcp-firewall':        { label: 'Firewall',        iconPath: '/icons/gcp-firewall.png',        category: 'Security',   tags: ['firewall', 'security', 'rule', 'gcp'] },
  'gcp-secret-manager':  { label: 'Secret Manager',  iconPath: '/icons/gcp-secret-manager.png',  category: 'Security',   tags: ['secret', 'manager', 'vault', 'gcp'] },
  'gcp-cloud-build':     { label: 'Cloud Build',     iconPath: '/icons/gcp-cloud-build.png',     category: 'DevOps',     tags: ['build', 'ci', 'cd', 'devops', 'pipeline', 'gcp'] },
  'gcp-artifact-registry':{ label:'Artifact Registry',iconPath:'/icons/gcp-artifact-registry.png',category:'DevOps',    tags: ['artifact', 'registry', 'docker', 'image', 'gcp'] },
  'gcp-cloud-scheduler': { label: 'Cloud Scheduler', iconPath: '/icons/gcp-cloud-scheduler.png', category: 'Management', tags: ['scheduler', 'cron', 'job', 'timer', 'gcp'] },
  'gcp-logging':         { label: 'Cloud Logging',   iconPath: '/icons/gcp-logging.png',         category: 'Management', tags: ['logging', 'log', 'audit', 'monitor', 'gcp'] },
  'gcp-monitoring':      { label: 'Cloud Monitoring',iconPath: '/icons/gcp-monitoring.png',      category: 'Management', tags: ['monitoring', 'metrics', 'alert', 'gcp'] },
  'gcp-vertex-ai':       { label: 'Vertex AI',       iconPath: '/icons/gcp-vertex-ai.png',       category: 'AI/ML',      tags: ['vertex', 'ai', 'ml', 'model', 'gcp'] },
  'gcp-api-gateway':     { label: 'API Gateway',     iconPath: '/icons/gcp-api-gateway.png',     category: 'Network',    tags: ['api', 'gateway', 'rest', 'gcp'] },
};

// ─── Azure service definitions ────────────────────────────────────────────────

export const AZURE_SERVICES: Record<string, CloudService> = {
  'azure-aks':                  { label: 'AKS',                   iconPath: '/icons/azure-aks.png',                  category: 'Compute',    tags: ['kubernetes', 'k8s', 'container', 'cluster', 'azure'] },
  'azure-vmss':                 { label: 'VMSS',                  iconPath: '/icons/azure-vmss.png',                 category: 'Compute',    tags: ['vm', 'scale', 'set', 'virtual', 'machine', 'azure'] },
  'azure-availability-sets':    { label: 'Availability Sets',     iconPath: '/icons/azure-availability-sets.png',    category: 'Compute',    tags: ['availability', 'set', 'ha', 'azure'] },
  'azure-container-instances':  { label: 'Container Instances',   iconPath: '/icons/azure-container-instances.png',  category: 'Compute',    tags: ['container', 'instance', 'docker', 'azure'] },
  'azure-app-gateway':          { label: 'App Gateway',           iconPath: '/icons/azure-app-gateway.png',          category: 'Network',    tags: ['app', 'gateway', 'load', 'balancer', 'azure'] },
  'azure-expressroute':         { label: 'ExpressRoute',          iconPath: '/icons/azure-expressroute.png',         category: 'Network',    tags: ['expressroute', 'circuit', 'private', 'azure'] },
  'azure-vpn-gateway':          { label: 'VPN Gateway',           iconPath: '/icons/azure-vpn-gateway.png',          category: 'Network',    tags: ['vpn', 'gateway', 'network', 'azure'] },
  'azure-traffic-manager':      { label: 'Traffic Manager',       iconPath: '/icons/azure-traffic-manager.png',      category: 'Network',    tags: ['traffic', 'manager', 'dns', 'routing', 'azure'] },
  'azure-network-interface':    { label: 'Network Interface',     iconPath: '/icons/azure-network-interface.png',    category: 'Network',    tags: ['nic', 'network', 'interface', 'azure'] },
  'azure-public-ip':            { label: 'Public IP',             iconPath: '/icons/azure-public-ip.png',            category: 'Network',    tags: ['public', 'ip', 'address', 'azure'] },
  'azure-storage-account':      { label: 'Storage Account',       iconPath: '/icons/azure-storage-account.png',      category: 'Storage',    tags: ['storage', 'blob', 'account', 'azure'] },
  'azure-snapshot':             { label: 'Snapshot',              iconPath: '/icons/azure-snapshot.png',             category: 'Storage',    tags: ['snapshot', 'backup', 'disk', 'azure'] },
  'azure-cosmos-db':            { label: 'Cosmos DB',             iconPath: '/icons/azure-cosmos-db.png',            category: 'Database',   tags: ['cosmos', 'nosql', 'database', 'azure'] },
  'azure-redis':                { label: 'Redis Cache',           iconPath: '/icons/azure-redis.png',                category: 'Database',   tags: ['redis', 'cache', 'memory', 'azure'] },
  'azure-event-hubs':           { label: 'Event Hubs',            iconPath: '/icons/azure-event-hubs.png',           category: 'Messaging',  tags: ['event', 'hub', 'streaming', 'kafka', 'azure'] },
  'azure-service-bus':          { label: 'Service Bus',           iconPath: '/icons/azure-service-bus.png',          category: 'Messaging',  tags: ['service', 'bus', 'queue', 'messaging', 'azure'] },
  'azure-nsg':                  { label: 'NSG',                   iconPath: '/icons/azure-nsg.png',                  category: 'Security',   tags: ['nsg', 'network', 'security', 'group', 'firewall', 'azure'] },
  'azure-container-registry':   { label: 'Container Registry',    iconPath: '/icons/azure-container-registry.png',   category: 'DevOps',     tags: ['acr', 'container', 'registry', 'docker', 'image', 'azure'] },
  'azure-search':               { label: 'Azure Search',          iconPath: '/icons/azure-search.png',               category: 'AI/ML',      tags: ['search', 'cognitive', 'ai', 'azure'] },
};

// Exported icon maps used by CloudMapPage applyElementStyle
export const GCP_DESIGN_ICONS: Record<string, string> = Object.fromEntries(
  Object.entries(GCP_SERVICES).map(([k, v]) => [k, v.iconPath])
);
export const AZURE_DESIGN_ICONS: Record<string, string> = Object.fromEntries(
  Object.entries(AZURE_SERVICES).map(([k, v]) => [k, v.iconPath])
);

// ─── Unified palette item type ────────────────────────────────────────────────

type PaletteItemType = 'aws' | 'shape' | 'icon' | 'gcp' | 'azure';

interface PaletteItem {
  id: string;
  label: string;
  section: string;
  tags: string[];
  kind: PaletteItemType;
  // payload
  awsType?:         string;
  cloudServiceType?: string;  // for gcp / azure
  shapeData?:       GenericShape;
  iconData?:        IconData;
  // visual
  awsIconPath?:  string;
  cloudIconPath?: string;     // for gcp / azure
  faIcon?:       string;
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
  addShapes(ER_SHAPES,         'Entity-Relationship');
  addShapes(UML_SHAPES,        'UML');
  addShapes(BPMN_SHAPES,       'BPMN');
  addShapes(WIREFRAME_SHAPES,  'Wireframe');

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

  // GCP services
  Object.entries(GCP_SERVICES).forEach(([type, svc]) => {
    items.push({
      id: `gcp-${type}`,
      label: svc.label,
      section: `GCP — ${svc.category}`,
      tags: svc.tags,
      kind: 'gcp',
      cloudServiceType: type,
      cloudIconPath: svc.iconPath,
    });
  });

  // Azure services
  Object.entries(AZURE_SERVICES).forEach(([type, svc]) => {
    items.push({
      id: `azure-${type}`,
      label: svc.label,
      section: `Azure — ${svc.category}`,
      tags: svc.tags,
      kind: 'azure',
      cloudServiceType: type,
      cloudIconPath: svc.iconPath,
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

const GCP_SECTIONS = Array.from(
  new Set(Object.values(GCP_SERVICES).map(s => `GCP — ${s.category}`))
).sort();

const AZURE_SECTIONS = Array.from(
  new Set(Object.values(AZURE_SERVICES).map(s => `Azure — ${s.category}`))
).sort();

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
    id: 'gcp',
    label: 'Google Cloud Platform',
    description: 'GCP service icons — Compute, Storage, BigQuery, and more',
    faIcon: 'fa-google',
    count: Object.keys(GCP_SERVICES).length,
  },
  {
    id: 'azure',
    label: 'Microsoft Azure',
    description: 'Azure service icons — AKS, Cosmos DB, Event Hubs, and more',
    faIcon: 'fa-microsoft',
    count: Object.keys(AZURE_SERVICES).length,
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
  const order = ['General', 'Flowchart', 'Network', 'Containers', 'Entity-Relationship', 'UML', 'BPMN', 'Wireframe'];
  if (enabled.has('aws'))   order.push(...AWS_SECTIONS);
  if (enabled.has('gcp'))   order.push(...GCP_SECTIONS);
  if (enabled.has('azure')) order.push(...AZURE_SECTIONS);
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
    if ((item.kind === 'gcp' || item.kind === 'azure') && item.cloudIconPath) {
      return <img src={item.cloudIconPath} alt={item.label} className="w-6 h-6 object-contain" />;
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
          ? ' border border-dashed border-current opacity-80 hover:bg-gray-100'
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
  onCloudServiceSelect?: (provider: 'gcp' | 'azure', serviceType: string, iconPath: string, label: string) => void;
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
          <div className="w-8 h-8 bg-gray-100 rounded-xl flex items-center justify-center shrink-0">
            <i className="fas fa-shapes text-gray-600 text-xs" />
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
              className="flex items-start gap-3 p-3 rounded-xl border border-gray-100 hover:border-gray-300 hover:bg-gray-50 cursor-pointer transition-colors"
            >
              <input
                type="checkbox"
                checked={draft.has(lib.id)}
                onChange={() => toggle(lib.id)}
                className="mt-0.5 accent-gray-700"
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
            className="flex-1 px-3 py-2 bg-gray-900 text-white text-xs font-semibold rounded-xl hover:bg-gray-800 transition-colors"
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
  mode, onDragStart, onShapeDragStart, onIconSelect, onShapeSelect, onAwsTypeSelect, onCloudServiceSelect,
}) => {
  // ── ALL hooks must come before any conditional return ──────────────────────
  const [query,            setQuery]            = useState('');
  const [openSections,     setOpenSections]     = useState<Set<string>>(
    () => new Set(['General', 'Flowchart', 'Network', 'Containers', 'Entity-Relationship', 'UML', 'BPMN', 'Wireframe'])
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
    } else if ((item.kind === 'gcp' || item.kind === 'azure') && item.cloudServiceType && item.cloudIconPath) {
      e.dataTransfer.setData('type', 'cloud-service');
      e.dataTransfer.setData('cloudProvider', item.kind);
      e.dataTransfer.setData('cloudServiceType', item.cloudServiceType);
      e.dataTransfer.setData('cloudIconPath', item.cloudIconPath);
      e.dataTransfer.setData('cloudLabel', item.label);
    }
  }, [onDragStart, onShapeDragStart]);

  const handleClick = useCallback((item: PaletteItem) => {
    if (item.kind === 'icon' && item.iconData)   onIconSelect?.(item.iconData);
    if (item.kind === 'shape' && item.shapeData) onShapeSelect?.(item.shapeData);
    if (item.kind === 'aws' && item.awsType)     onAwsTypeSelect?.(item.awsType);
    if ((item.kind === 'gcp' || item.kind === 'azure') && item.cloudServiceType && item.cloudIconPath) {
      onCloudServiceSelect?.(item.kind, item.cloudServiceType, item.cloudIconPath, item.label);
    }
  }, [onIconSelect, onShapeSelect, onAwsTypeSelect, onCloudServiceSelect]);

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
              placeholder="Search shapes, AWS, GCP, Azure…"
              onKeyDown={e => {
                if (e.key === '/' && !query) { e.preventDefault(); searchRef.current?.focus(); return; }
                if (e.key === 'Escape') { e.preventDefault(); setQuery(''); searchRef.current?.blur(); return; }
                // Enter: add the first search result to the canvas
                if (e.key === 'Enter' && filteredItems.length > 0) { e.preventDefault(); handleClick(filteredItems[0]); }
              }}
              className="w-full px-2 pr-6 py-1 text-[11px] bg-white border border-gray-300 rounded focus:outline-none focus:border-gray-500 placeholder-gray-400"
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
            className="w-full flex items-center justify-center gap-1.5 px-3 py-1.5 text-[11px] font-semibold text-gray-600 border border-gray-200 rounded-lg hover:bg-gray-100 transition-colors"
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
