import React from 'react';

interface CloudIcon {
  icon: string;
  label: string;
}

const cloudIcons: CloudIcon[] = [
  { icon: 'fab fa-aws', label: 'AWS' },
  { icon: 'fab fa-google', label: 'GCP' },
  { icon: 'fab fa-microsoft', label: 'Azure' },
  { icon: 'fas fa-dollar-sign', label: 'FinOps' },
  { icon: 'fas fa-cogs', label: 'DevOps' },
  { icon: 'fas fa-chart-line', label: 'Analytics' },
];

const CloudProviderIcons: React.FC = () => {
  return (
    <div className="cloud-icons">
      {cloudIcons.map((item, index) => (
        <div key={index} className="cloud-icon">
          <i className={item.icon}></i>
          <span>{item.label}</span>
        </div>
      ))}
    </div>
  );
};

export default CloudProviderIcons;
