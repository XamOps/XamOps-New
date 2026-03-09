/**
 * WasteResourceGroup Component
 *
 * Displays a group of wasted resources by service type with expand/collapse functionality
 */

import React, { useState } from 'react';
import type { WastedResource } from '../../types/waste';

interface WasteResourceGroupProps {
  serviceType: string;
  resources: WastedResource[];
  isOpen: boolean;
  onToggle: () => void;
}

const getServiceIcon = (serviceType: string): string => {
  const iconMap: Record<string, string> = {
    'EC2 Instance': 'ec2-instances.png',
    'EBS Volume': 'aws-ebs.png',
    'RDS Instance': 'rds-instance.png',
    'Lambda': 'aws-lambda.png',
    'S3 Bucket': 's3-bucket.png',
    'Load Balancer': 'load-balancer.png',
    'Auto Scaling Group': 'autoscaling.png',
    'VPC': 'vpc.png',
    'Security Group': 'security-group.png',
    'Route 53 Zone': 'aws-route53.png',
    'ElastiCache Cluster': 'elasticache-cluster.png',
    'DynamoDB Table': 'dynamodb-table.png',
    'ECR Repository': 'ecr-repository.png',
    'CloudTrail': 'cloudtrail.png',
    'Certificate Manager': 'certificate-manager.png',
    'CloudWatch Log Group': 'cloudwatch-log-group.png',
    'SNS Topic': 'sns-topic.png',
    'SQS Queue': 'sqs-queue.png',
    'Elastic IP': 'elastic-ip.png',
    'Snapshot': 'snapshot.png',
    'AMI': 'ami.png',
    'ENI': 'eni.png',
    'EKS Cluster': 'aws-eks.png',
    'ECS Cluster': 'aws-ecs.png',
    'DB Snapshot': 'rds-instance.png',
    'Log Group': 'cloudwatch-log-group.png',
  };
  return iconMap[serviceType] || 'default-icon.png';
};

const WasteResourceGroup: React.FC<WasteResourceGroupProps> = ({
  serviceType,
  resources,
  isOpen,
  onToggle,
}) => {
  const [expandedResources, setExpandedResources] = useState<Set<string>>(new Set());

  const toggleResourceDetails = (resourceId: string) => {
    setExpandedResources((prev) => {
      const newSet = new Set(prev);
      if (newSet.has(resourceId)) {
        newSet.delete(resourceId);
      } else {
        newSet.add(resourceId);
      }
      return newSet;
    });
  };

  return (
    <div className="service-group fade-in">
      {/* Group Header */}
      <div
        onClick={onToggle}
        className="flex items-center justify-between p-6 cursor-pointer hover:bg-gray-50/50 dark:hover:bg-gray-800/30 transition-colors"
      >
        <div className="flex items-center space-x-4 flex-1">
          <i
            className={`fas fa-chevron-right text-gray-400 dark:text-gray-500 w-5 text-center transition-all ${
              isOpen ? 'rotate-90' : ''
            }`}
          ></i>
          <div className="service-icon w-12 h-12 flex items-center justify-center rounded-xl shadow-sm">
            <img
              src={`/icons/${getServiceIcon(serviceType)}`}
              alt={serviceType}
              className="w-full h-full rounded-xl object-cover"
              onError={(e) => {
                (e.target as HTMLImageElement).src = '/icons/default-icon.png';
              }}
            />
          </div>
          <div className="flex-1">
            <h3 className="font-bold text-gray-800 dark:text-gray-200 text-lg">{serviceType}</h3>
            <p className="text-sm text-gray-600 dark:text-gray-400">Wasted Resource Category</p>
          </div>
          <div className="resource-counter text-sm font-bold px-4 py-2 rounded-full">
            {resources.length}
          </div>
        </div>
      </div>

      {/* Resource List */}
      {isOpen && (
        <div className="bg-gradient-to-r from-gray-50/10 to-gray-100/10 dark:from-gray-800/20 dark:to-gray-900/20">
          {resources.map((resource) => {
            const isDetailsOpen = expandedResources.has(resource.resourceId);

            return (
              <div key={resource.resourceId} className="resource-row border-t border-gray-200/30 dark:border-gray-700/30">
                {/* Resource Row */}
                <div
                  role="button"
                  tabIndex={0}
                  aria-expanded={isDetailsOpen}
                  aria-controls={`waste-detail-${resource.resourceId}`}
                  onClick={() => toggleResourceDetails(resource.resourceId)}
                  onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggleResourceDetails(resource.resourceId); } }}
                  className="grid grid-cols-12 items-center p-6 pl-16 text-sm cursor-pointer"
                >
                  <div className="col-span-5 flex items-center space-x-4">
                    <i
                      className={`fas fa-chevron-right text-gray-400 dark:text-gray-500 w-5 text-center transition-all ${
                        isDetailsOpen ? 'rotate-90' : ''
                      }`}
                    ></i>
                    <div className="flex-1 min-w-0">
                      <p className="font-bold text-gray-800 dark:text-gray-200 truncate">
                        {resource.resourceName || 'Untitled'}
                      </p>
                      <p className="font-mono text-xs text-gray-500 dark:text-gray-400 truncate">
                        {resource.resourceId}
                      </p>
                    </div>
                  </div>
                  <div className="col-span-2 flex items-center space-x-2">
                    <i className="fas fa-map-marker-alt text-gray-400 dark:text-gray-500 text-xs"></i>
                    <span className="font-semibold text-gray-600 dark:text-gray-300">{resource.region}</span>
                  </div>
                  <div className="col-span-2 flex items-center space-x-1">
                    <i className="fas fa-dollar-sign text-red-500 dark:text-red-400 text-xs"></i>
                    <span className="font-bold text-red-600 dark:text-red-400">
                      {resource.monthlySavings.toFixed(2)}
                    </span>
                  </div>
                  <div className="col-span-3 text-right pr-4">
                    <div className="flex justify-end gap-2" onClick={(e) => e.stopPropagation()}>
                      <button
                        disabled
                        title="Delete (Coming Soon)"
                        className="action-button px-3 py-2 text-xs font-semibold text-white bg-red-600 rounded-lg opacity-50 cursor-not-allowed"
                      >
                        <i className="fas fa-trash-alt"></i>
                      </button>
                      <button
                        disabled
                        title="Archive (Coming Soon)"
                        className="action-button px-3 py-2 text-xs font-semibold text-gray-700 bg-gray-200 rounded-lg opacity-50 cursor-not-allowed"
                      >
                        <i className="fas fa-archive"></i>
                      </button>
                    </div>
                  </div>
                </div>

                {/* Resource Details */}
                {isDetailsOpen && (
                  <div id={`waste-detail-${resource.resourceId}`} className="resource-details p-6 pl-24 text-sm text-gray-700 dark:text-gray-300 bg-gray-50/20 dark:bg-gray-800/20 border-t border-gray-200/30 dark:border-gray-700/30">
                    <h4 className="font-semibold text-base mb-3 text-gray-800 dark:text-gray-200">Waste Details:</h4>
                    <div className="grid grid-cols-2 gap-3">
                      <div className="flex items-start">
                        <strong className="text-gray-600 dark:text-gray-400 mr-2">Reason:</strong>
                        <span className="text-gray-700 dark:text-gray-300">{resource.reason}</span>
                      </div>
                      <div className="flex items-start">
                        <strong className="text-gray-600 dark:text-gray-400 mr-2">Monthly Cost:</strong>
                        <span className="text-red-600 dark:text-red-400 font-semibold">
                          ${resource.monthlySavings.toFixed(2)}
                        </span>
                      </div>
                      <div className="flex items-start">
                        <strong className="text-gray-600 dark:text-gray-400 mr-2">Region:</strong>
                        <span className="text-gray-700 dark:text-gray-300">{resource.region}</span>
                      </div>
                      <div className="flex items-start">
                        <strong className="text-gray-600 dark:text-gray-400 mr-2">Resource Type:</strong>
                        <span className="text-gray-700 dark:text-gray-300">{resource.resourceType}</span>
                      </div>
                    </div>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
};

export default WasteResourceGroup;
