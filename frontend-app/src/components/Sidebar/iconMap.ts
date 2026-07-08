/**
 * Maps the FontAwesome icon tokens used across the sidebar to Lucide (outline)
 * equivalents rendered via @iconify/react.
 *
 * Keyed by the `fa-*` token only (e.g. "fa-server"), so "fas fa-server",
 * "far fa-server", etc. all resolve. Any token missing from this map falls back
 * to the original FontAwesome glyph in <NavIcon>, so nothing ever renders blank.
 */
export const FA_TO_LUCIDE: Record<string, string> = {
  // Dashboards / charts
  'fa-tachometer-alt': 'lucide:gauge',
  'fa-chart-line': 'lucide:line-chart',
  'fa-chart-bar': 'lucide:bar-chart-3',
  'fa-chart-pie': 'lucide:pie-chart',

  // FinOps / billing
  'fa-dollar-sign': 'lucide:circle-dollar-sign',
  'fa-file-invoice': 'lucide:file-text',
  'fa-file-invoice-dollar': 'lucide:receipt',
  'fa-coins': 'lucide:coins',
  'fa-file-contract': 'lucide:file-check',
  'fa-file-signature': 'lucide:file-signature',
  'fa-recycle': 'lucide:recycle',
  'fa-compress-arrows-alt': 'lucide:shrink',
  'fa-hdd': 'lucide:hard-drive',
  'fa-clock': 'lucide:clock',
  'fa-microchip': 'lucide:cpu',
  'fa-magic': 'lucide:wand-2',
  'fa-piggy-bank': 'lucide:piggy-bank',
  'fa-store': 'lucide:store',
  'fa-history': 'lucide:history',

  // CloudOps
  'fa-server': 'lucide:server',
  'fa-dharmachakra': 'lucide:ship-wheel',
  'fa-cogs': 'lucide:settings-2',
  'fa-toolbox': 'lucide:box',
  'fa-brain': 'lucide:brain',
  'fa-database': 'lucide:database',

  // SecOps / observability / SRE
  'fa-shield-alt': 'lucide:shield-check',
  'fa-satellite-dish': 'lucide:satellite-dish',
  'fa-list-alt': 'lucide:list',
  'fa-project-diagram': 'lucide:workflow',
  'fa-bell': 'lucide:bell',
  'fa-stethoscope': 'lucide:stethoscope',
  'fa-search': 'lucide:search',

  // MediaOps
  'fa-film': 'lucide:film',
  'fa-exchange-alt': 'lucide:arrow-left-right',
  'fa-broadcast-tower': 'lucide:radio-tower',

  // Support / tickets / misc
  'fa-ticket-alt': 'lucide:ticket',
  'fa-headset': 'lucide:headset',
  'fa-clipboard-list': 'lucide:clipboard-list',
  'fa-tools': 'lucide:wrench',
  'fa-key': 'lucide:key-round',
  'fa-cloud': 'lucide:cloud',
  'fa-cog': 'lucide:settings',

  // Users / access
  'fa-users': 'lucide:users',
  'fa-users-cog': 'lucide:users',
  'fa-users-gear': 'lucide:users',
  'fa-user-cog': 'lucide:user-cog',
  'fa-user': 'lucide:user',
  'fa-layer-group': 'lucide:layers',
  'fa-sign-out-alt': 'lucide:log-out',
};

/**
 * Resolves a FontAwesome class string (e.g. "fas fa-server") to its Lucide
 * equivalent, or `undefined` if there is no mapping.
 */
export function resolveLucide(faClass: string): string | undefined {
  const token = faClass.split(/\s+/).find((c) => c.startsWith('fa-'));
  return token ? FA_TO_LUCIDE[token] : undefined;
}
