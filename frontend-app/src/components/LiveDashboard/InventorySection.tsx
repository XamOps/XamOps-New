import { useNavigate } from 'react-router-dom';
import { Icon } from '@iconify/react';
import type { ResourceInventory } from '../../types/liveDashboard';

interface InventorySectionProps {
  /**
   * Absent when the dashboard payload carried no inventory.
   *
   * <p>Typed optional because it genuinely arrives missing — DashboardData declares
   * `resourceInventory` as non-optional, but a degraded or unscanned account returns nothing for
   * it, which is why this component needed a null guard in the first place.
   */
  inventory?: ResourceInventory | null;
  isLoading: boolean;
  /** Returns null when the feature has no page for the selected provider. */
  getLink: (path: string) => string | null;
}

interface ResourceCardProps {
  name: string;
  count: number;
  iconName: string;
  iconColor: string;
  service: string;
  isLast?: boolean;
}

const ResourceCard: React.FC<ResourceCardProps & { animIndex: number }> = ({ name, count, iconName, iconColor, service, animIndex }) => {
  const navigate = useNavigate();

  return (
    <button
      onClick={() => navigate(`/cloudlist?service=${service}`)}
      className="w-full flex items-center justify-between px-3 py-2.5 hover:bg-[rgba(0,0,0,0.025)] transition-colors text-left group"
      style={{ animation: `fadeInUp 0.4s cubic-bezier(0.22, 1, 0.36, 1) ${animIndex * 0.055}s both` }}
    >
      <div className="flex items-center gap-3">
        <div className="w-9 h-9 rounded-lg flex items-center justify-center flex-shrink-0" style={{ backgroundColor: `${iconColor}14` }}>
          {iconName.startsWith('/')
            ? <img src={iconName} width={20} height={20} style={{ objectFit: 'contain' }} />
            : <Icon icon={iconName} width={20} height={20} color={iconColor} />}
        </div>
        <div>
          <p className="text-[14px] font-normal text-[rgba(0,0,0,0.88)] leading-5">{name}</p>
          <p className="text-[10px] text-[rgba(0,0,0,0.35)] uppercase tracking-wide leading-tight">Active Count</p>
        </div>
      </div>
      <span
        className="text-[24px] font-extrabold text-[rgba(0,0,0,0.88)] tabular-nums flex-shrink-0"
        style={{ fontFamily: "'Barlow Condensed', 'Inter', sans-serif", fontVariantNumeric: 'tabular-nums', letterSpacing: '-0.01em' }}
      >
        {count}
      </span>
    </button>
  );
};

const InventorySection: React.FC<InventorySectionProps> = ({ inventory, isLoading }) => {
  /*
   * Deliberately no early `return null` on a missing inventory.
   *
   * This panel occupies 2 of the 5 columns beside Cost Trend. Rendering nothing did not remove a
   * row, it left that cell empty, so Cost Trend sat at 3/5 width against a blank gap and the
   * dashboard looked like the widget had been deleted rather than like the data had not arrived.
   * The sibling Compliance Posture panel keeps its frame and says "Report unavailable"; this one
   * now does the same, and the two empty states below say which of the two situations it is.
   */
  const allResourceItems = !inventory ? [] : [
    { name: 'EC2 Instances',    count: inventory.ec2Count,          iconName: 'simple-icons:amazonec2',               iconColor: '#FF9900', service: 'EC2' },
    { name: 'S3 Buckets',       count: inventory.s3Count,           iconName: 'simple-icons:amazons3',                iconColor: '#FF9900', service: 'S3' },
    { name: 'RDS Databases',    count: inventory.rdsCount,          iconName: 'simple-icons:amazonrds',               iconColor: '#527FFF', service: 'RDS' },
    { name: 'VPC Networks',     count: inventory.vpcCount,          iconName: 'mdi:lan',                              iconColor: '#8C4FFF', service: 'VPC' },
    { name: 'Lambda Functions', count: inventory.lambdaCount,       iconName: '/icons/aws-lambda-function.svg', iconColor: '#4D27AA', service: 'Lambda' },
    { name: 'EBS Volumes',      count: inventory.ebsCount,          iconName: '/icons/aws-ebs-volume.svg',      iconColor: '#3F8624', service: 'EBS' },
    { name: 'Route53 Zones',    count: inventory.route53Count,      iconName: '/icons/aws-route53-zone.svg',    iconColor: '#4D27AA', service: 'Route53' },
    { name: 'Load Balancers',   count: inventory.loadBalancerCount, iconName: '/icons/aws-alb.svg',             iconColor: '#4D27AA', service: 'ELB' },
    { name: 'ECS Clusters',     count: inventory.ecsCount,          iconName: 'simple-icons:amazonecs',               iconColor: '#FF9900', service: 'ECS' },
    { name: 'EKS Clusters',     count: inventory.eksCount,          iconName: 'simple-icons:amazoneks',               iconColor: '#FF9900', service: 'EKS' },
    { name: 'Lightsail',        count: inventory.lightsailCount,    iconName: '/icons/lightsail.png',                 iconColor: '#FF9900', service: 'Lightsail' },
    { name: 'Amplify Apps',     count: inventory.amplifyCount,      iconName: 'simple-icons:awsamplify',              iconColor: '#FF9900', service: 'Amplify' },
  ];

  const resourceItems = allResourceItems.filter(item => item.count > 0);

  return (
    <div className="lg:col-span-2 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm flex flex-col overflow-hidden h-full widget-panel-hover">
      {/* Header */}
      <div className="flex items-center justify-between px-5 pt-5 pb-4 flex-shrink-0">
        <div>
          <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Resource Inventory</p>
          <p className="text-[11px] text-[rgba(0,0,0,0.4)] flex items-center gap-1.5 mt-0.5">
            <span className="w-1.5 h-1.5 rounded-full bg-[#1BE68B] animate-pulse inline-block"></span>
            Live Infrastructure
          </p>
        </div>
        <div className="w-7 h-7 flex items-center justify-center rounded-md border border-[rgba(0,0,0,0.06)]">
          <svg className={`w-3.5 h-3.5 text-[rgba(0,0,0,0.3)] ${isLoading ? 'animate-spin' : ''}`} fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
          </svg>
        </div>
      </div>

      {/* List */}
      {isLoading ? (
        <div className="px-5 pb-5 space-y-2">
          {[1,2,3,4,5].map(i => (
            <div key={i} className="h-[46px] bg-[rgba(0,0,0,0.04)] rounded-lg animate-pulse"></div>
          ))}
        </div>
      ) : (
        <div className="flex-1 overflow-y-auto inventory-hide-scrollbar" style={{ scrollbarWidth: 'none' }}>
          {resourceItems.length > 0 ? (
            resourceItems.map((resource, idx) => (
              <ResourceCard key={resource.service} {...resource} animIndex={idx} isLast={idx === resourceItems.length - 1} />
            ))
          ) : (
            /* Two distinct states: a scan that found nothing, and no scan result at all. Collapsing
               them would let an unscanned account read as a confirmed-empty one. */
            <div className="py-12 px-5 text-center">
              <Icon
                icon={inventory ? 'mdi:server-off-outline' : 'mdi:cloud-question-outline'}
                width={26}
                className="mx-auto mb-2 text-[rgba(0,0,0,0.18)]"
              />
              {inventory ? (
                <p className="text-[13px] text-[rgba(0,0,0,0.35)]">No active resources found</p>
              ) : (
                <>
                  <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.5)]">Inventory unavailable</p>
                  <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-1 leading-relaxed">
                    This account returned no resource inventory. Refresh to run a fresh scan.
                  </p>
                </>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
};

export default InventorySection;
