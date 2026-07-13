import React from 'react';
import { Icon } from '@iconify/react';

/**
 * Shared compliance-framework branding (icons, brand colors, SVG logos) used by
 * ComplianceOps and XamSecure Compliance so both render frameworks identically.
 */

export const FRAMEWORK_CONFIG: Record<string, { displayName: string; icon: string; color: string; bgColor: string; borderColor: string }> = {
  'CIS':                { displayName: 'CIS Benchmark',       icon: 'mdi:shield-half-full',       color: 'text-blue-600',    bgColor: 'bg-blue-50',    borderColor: 'border-blue-200'    },
  'SOC2':               { displayName: 'SOC 2',               icon: 'mdi:lock-outline',            color: 'text-purple-600',  bgColor: 'bg-purple-50',  borderColor: 'border-purple-200'  },
  'PCI-DSS':            { displayName: 'PCI DSS',             icon: 'mdi:credit-card-outline',     color: 'text-green-600',   bgColor: 'bg-green-50',   borderColor: 'border-green-200'   },
  'HIPAA':              { displayName: 'HIPAA',               icon: 'mdi:heart-pulse',             color: 'text-red-600',     bgColor: 'bg-red-50',     borderColor: 'border-red-200'     },
  'NIST':               { displayName: 'NIST CSF',            icon: 'mdi:bank-outline',            color: 'text-indigo-600',  bgColor: 'bg-indigo-50',  borderColor: 'border-indigo-200'  },
  'ISO27001':           { displayName: 'ISO 27001',           icon: 'mdi:certificate-outline',     color: 'text-teal-600',    bgColor: 'bg-teal-50',    borderColor: 'border-teal-200'    },
  'GDPR':               { displayName: 'GDPR',                icon: 'mdi:account-shield-outline',  color: 'text-amber-600',   bgColor: 'bg-amber-50',   borderColor: 'border-amber-200'   },
  'MITRE':              { displayName: 'MITRE ATT&CK',        icon: 'mdi:crosshairs',              color: 'text-rose-600',    bgColor: 'bg-rose-50',    borderColor: 'border-rose-200'    },
  'CISA':               { displayName: 'CISA',                icon: 'mdi:flag-variant',            color: 'text-sky-600',     bgColor: 'bg-sky-50',     borderColor: 'border-sky-200'     },
  'FEDRAMP':            { displayName: 'FedRAMP',             icon: 'mdi:bank-outline',            color: 'text-blue-700',    bgColor: 'bg-blue-50',    borderColor: 'border-blue-200'    },
  'FFIEC':              { displayName: 'FFIEC',               icon: 'mdi:scale-balance',           color: 'text-cyan-600',    bgColor: 'bg-cyan-50',    borderColor: 'border-cyan-200'    },
  'RBI':                { displayName: 'RBI CSF',             icon: 'mdi:currency-inr',            color: 'text-orange-600',  bgColor: 'bg-orange-50',  borderColor: 'border-orange-200'  },
  'ENS':                { displayName: 'ENS',                 icon: 'mdi:earth',                   color: 'text-yellow-600',  bgColor: 'bg-yellow-50',  borderColor: 'border-yellow-200'  },
  'AWS_WELL_ARCHITECTED': { displayName: 'AWS Well-Architected', icon: 'mdi:cloud-outline',        color: 'text-orange-600',  bgColor: 'bg-orange-50',  borderColor: 'border-orange-200'  },
  'FSBP':               { displayName: 'AWS FSBP',            icon: 'mdi:cloud-outline',           color: 'text-amber-700',   bgColor: 'bg-amber-50',   borderColor: 'border-amber-200'   },
  'GXP':                { displayName: 'GxP',                 icon: 'mdi:flask-outline',           color: 'text-emerald-600', bgColor: 'bg-emerald-50', borderColor: 'border-emerald-200' },
  'NIS2':               { displayName: 'NIS2',                icon: 'mdi:network-outline',         color: 'text-violet-600',  bgColor: 'bg-violet-50',  borderColor: 'border-violet-200'  },
  'NIST800':            { displayName: 'NIST 800-53',         icon: 'mdi:book-outline',            color: 'text-indigo-700',  bgColor: 'bg-indigo-50',  borderColor: 'border-indigo-200'  },
  'CSA_CCM':            { displayName: 'CSA CCM',             icon: 'mdi:cloud-outline',           color: 'text-blue-500',    bgColor: 'bg-blue-50',    borderColor: 'border-blue-200'    },
  'CMMC':               { displayName: 'CMMC',                icon: 'mdi:shield-bug-outline',      color: 'text-gray-700',    bgColor: 'bg-gray-100',   borderColor: 'border-gray-300'    },
  'KISA':               { displayName: 'KISA ISMS-P',         icon: 'mdi:earth',                   color: 'text-pink-600',    bgColor: 'bg-pink-50',    borderColor: 'border-pink-200'    },
  'BSI_C5':             { displayName: 'BSI C5',              icon: 'mdi:shield-half-full',        color: 'text-gray-600',    bgColor: 'bg-gray-50',    borderColor: 'border-gray-200'    },
  'SECNUMCLOUD':        { displayName: 'SecNumCloud',         icon: 'mdi:cloud-upload-outline',    color: 'text-blue-600',    bgColor: 'bg-blue-50',    borderColor: 'border-blue-200'    },
  'CUSTOM':             { displayName: 'Best Practices',      icon: 'mdi:star-outline',            color: 'text-yellow-600',  bgColor: 'bg-yellow-50',  borderColor: 'border-yellow-200'  },
  'AWS_FTR':            { displayName: 'AWS FTR',             icon: 'mdi:cloud-outline',           color: 'text-orange-600',  bgColor: 'bg-orange-50',  borderColor: 'border-orange-200'  },
  'SEBI':               { displayName: 'SEBI',                icon: 'mdi:chart-line',              color: 'text-blue-600',    bgColor: 'bg-blue-50',    borderColor: 'border-blue-200'    },
  'IRDAI':              { displayName: 'IRDAI',               icon: 'mdi:shield-half-full',        color: 'text-green-700',   bgColor: 'bg-green-50',   borderColor: 'border-green-200'   },
  'DORA':               { displayName: 'DORA',                icon: 'mdi:bank-outline',            color: 'text-blue-700',    bgColor: 'bg-blue-50',    borderColor: 'border-blue-200'    },
};

export const DEFAULT_FW_CONFIG = { displayName: 'Other', icon: 'mdi:file-document-outline', color: 'text-gray-600', bgColor: 'bg-gray-50', borderColor: 'border-gray-200' };

export const LOGO_OVERRIDE: Record<string, { text: string; textColor: string; bg: string }> = {
  'CIS':               { text: 'CIS',   textColor: '#fff', bg: '#2563eb' },
  'SOC2':              { text: 'SOC2',  textColor: '#fff', bg: '#7c3aed' },
  'PCI-DSS':           { text: 'PCI',   textColor: '#fff', bg: '#16a34a' },
  'HIPAA':             { text: '⚕',    textColor: '#0d9488', bg: '#f0fdfa' },
  'NIST':              { text: 'NIST',  textColor: '#fff', bg: '#1e3a8a' },
  'NIST800':           { text: 'NIST',  textColor: '#fff', bg: '#312e81' },
  'ISO27001':          { text: 'ISO',   textColor: '#1e40af', bg: '#eff6ff' },
  'GDPR':              { text: 'GDPR',  textColor: '#fff', bg: '#b45309' },
  'MITRE':             { text: '&',     textColor: '#ea580c', bg: '#fff7ed' },
  'CISA':              { text: '🛡',    textColor: '#dc2626', bg: '#fef2f2' },
  'FEDRAMP':           { text: 'FR',    textColor: '#fff', bg: '#1e3a5f' },
  'FFIEC':             { text: 'FFIEC', textColor: '#fff', bg: '#0e7490' },
  'RBI':               { text: 'RBI',   textColor: '#fff', bg: '#9a3412' },
  'ENS':               { text: 'ENS',   textColor: '#fff', bg: '#ea580c' },
  'AWS_WELL_ARCHITECTED': { text: '☁', textColor: '#f97316', bg: '#fff7ed' },
  'FSBP':              { text: 'AWS',   textColor: '#fff', bg: '#ff9900' },
  'AWS_FTR':           { text: 'AWS',   textColor: '#fff', bg: '#ff9900' },
  'GXP':               { text: 'GxP',   textColor: '#fff', bg: '#065f46' },
  'NIS2':              { text: 'NIS2',  textColor: '#fff', bg: '#6d28d9' },
  'CSA_CCM':           { text: 'CSA',   textColor: '#fff', bg: '#0369a1' },
  'CMMC':              { text: 'CMMC',  textColor: '#fff', bg: '#374151' },
  'KISA':              { text: 'KISA',  textColor: '#fff', bg: '#9d174d' },
  'BSI_C5':            { text: 'BSI',   textColor: '#fff', bg: '#1f2937' },
  'SECNUMCLOUD':       { text: 'SNC',   textColor: '#fff', bg: '#1d4ed8' },
  'CUSTOM':            { text: 'BP',    textColor: '#fff', bg: '#92400e' },
  'SEBI':              { text: 'SEBI',  textColor: '#fff', bg: '#1e40af' },
  'IRDAI':             { text: 'IRDAI', textColor: '#fff', bg: '#166534' },
  'DORA':              { text: 'DORA',  textColor: '#fff', bg: '#1d4ed8' },
};

export const FRAMEWORK_LOGO_PATH: Record<string, string> = {
  'CIS': '/framework-logos/cis.svg',
  'SOC2': '/framework-logos/soc2.svg',
  'PCI-DSS': '/framework-logos/pci.svg',
  'HIPAA': '/framework-logos/hipaa.svg',
  'NIST': '/framework-logos/nist.svg',
  'NIST800': '/framework-logos/nist800.svg',
  'ISO27001': '/framework-logos/iso27001.svg',
  'GDPR': '/framework-logos/gdpr.svg',
  'MITRE': '/framework-logos/mitre.svg',
  'CISA': '/framework-logos/cisa.svg',
  'FEDRAMP': '/framework-logos/fedramp.svg',
  'FFIEC': '/framework-logos/ffiec.svg',
  'RBI': '/framework-logos/rbi.svg',
  'ENS': '/framework-logos/ens.svg',
  'AWS_WELL_ARCHITECTED': '/framework-logos/aws.svg',
  'FSBP': '/framework-logos/fsbp.svg',
  'AWS_FTR': '/framework-logos/aws.svg',
  'GXP': '/framework-logos/gxp.svg',
  'NIS2': '/framework-logos/nis2.svg',
  'CSA_CCM': '/framework-logos/csa.svg',
  'CMMC': '/framework-logos/cmmc.svg',
  'SECNUMCLOUD': '/framework-logos/secnumcloud.svg',
};

export function getLogoFontSize(text: string): string {
  if (text.length === 1) return '18px';
  if (text.length === 2) return '13px';
  if (text.length === 3) return '11px';
  if (text.length === 4) return '9px';
  return '7px';
}

/** Normalise an arbitrary framework label (e.g. "NIST SP 800-53") to a config key. */
export function getFrameworkKey(fw: string): string {
  const u = (fw || '').toUpperCase();
  if (u.includes('CIS')) return 'CIS';
  if (u.includes('SOC')) return 'SOC2';
  if (u.includes('PCI')) return 'PCI-DSS';
  if (u.includes('HIPAA')) return 'HIPAA';
  if (u.includes('NIST') && u.includes('800')) return 'NIST800';
  if (u.includes('NIST') && u.includes('CSF')) return 'NIST';
  if (u.includes('NIST')) return 'NIST';
  if (u.includes('ISO')) return 'ISO27001';   // 27001 / 27002 / IEC — ISO family
  if (u.includes('DORA') || u.includes('DIGITAL OPERATIONAL RESILIENCE')) return 'DORA';
  if (u.includes('GDPR')) return 'GDPR';
  if (u.includes('MITRE')) return 'MITRE';
  if (u.includes('CISA')) return 'CISA';
  if (u.includes('FEDRAMP') || u.includes('FED_RAMP')) return 'FEDRAMP';
  if (u.includes('FFIEC')) return 'FFIEC';
  if (u.includes('RBI')) return 'RBI';
  if (u.includes('ENS')) return 'ENS';
  if (u.includes('WELL') && u.includes('ARCHITECTED')) return 'AWS_WELL_ARCHITECTED';
  if (u.includes('FSBP') || (u.includes('FOUNDATIONAL') && u.includes('SECURITY'))) return 'FSBP';
  if (u.includes('GXP')) return 'GXP';
  if (u.includes('NIS2')) return 'NIS2';
  if (u.includes('CSA') || u.includes('CCM')) return 'CSA_CCM';
  if (u.includes('CMMC')) return 'CMMC';
  if (u.includes('KISA') || u.includes('ISMS')) return 'KISA';
  if (u.includes('BSI') && u.includes('C5')) return 'BSI_C5';
  if (u.includes('SECNUM')) return 'SECNUMCLOUD';
  if (u.includes('FTR') || u.includes('FOUNDATIONAL TECHNICAL')) return 'AWS_FTR';
  if (u.includes('SEBI')) return 'SEBI';
  if (u.includes('IRDAI')) return 'IRDAI';
  if (u.includes('CUSTOM') || u.includes('BEST PRACTICE')) return 'CUSTOM';
  return fw;
}

export function getFwConfig(key: string) {
  return FRAMEWORK_CONFIG[key] || { ...DEFAULT_FW_CONFIG, displayName: key };
}

/** True when we have a real logo (SVG file or brand text badge) for this framework. */
export function hasFrameworkLogo(framework: string): boolean {
  const key = getFrameworkKey(framework);
  return !!FRAMEWORK_LOGO_PATH[key] || !!LOGO_OVERRIDE[key];
}

interface FrameworkLogoProps {
  /** Raw framework label or a normalised key. */
  framework: string;
  /** Pixel size of the round badge. */
  size?: number;
}

/** Round framework logo: SVG file → brand text badge → mdi icon fallback. */
export const FrameworkLogo: React.FC<FrameworkLogoProps> = ({ framework, size = 24 }) => {
  const key = getFrameworkKey(framework);
  const cfg = getFwConfig(key);
  const logoPath = FRAMEWORK_LOGO_PATH[key];
  const logo = LOGO_OVERRIDE[key];
  const dim = { width: size, height: size };
  return (
    <div title={cfg.displayName}
         className="rounded-full flex items-center justify-center flex-shrink-0 border border-black/10 overflow-hidden"
         style={dim}>
      {logoPath ? (
        <img src={logoPath} alt={cfg.displayName} className="rounded-full object-cover" style={dim} />
      ) : logo ? (
        <div className="w-full h-full flex items-center justify-center rounded-full" style={{ backgroundColor: logo.bg }}>
          <span className="font-bold leading-none select-none"
                style={{ fontSize: getLogoFontSize(logo.text), color: logo.textColor }}>{logo.text}</span>
        </div>
      ) : (
        <Icon icon={cfg.icon} width={Math.round(size * 0.55)} className={cfg.color} />
      )}
    </div>
  );
};
