/**
 * AddAccountHeader Component
 *
 * Header section for the Add Account page with provider icon and back button
 */

import React from 'react';
import { Link } from 'react-router-dom';
import type { Provider } from '../../types/addAccount';

interface AddAccountHeaderProps {
  provider: Provider;
  headerText: string;
}

const AddAccountHeader: React.FC<AddAccountHeaderProps> = ({ provider, headerText }) => {
  const getProviderIcon = () => {
    switch (provider) {
      case 'aws': return 'fab fa-aws';
      case 'gcp': return 'fab fa-google';
      case 'azure': return 'fab fa-microsoft';
      case 'jenkins': return 'fab fa-jenkins';
      default: return 'fas fa-cloud';
    }
  };

  const getProviderName = () => {
    switch (provider) {
      case 'aws': return 'AWS';
      case 'gcp': return 'GCP';
      case 'azure': return 'Azure';
      case 'jenkins': return 'Jenkins';
      default: return 'Cloud';
    }
  };

  const getProviderClass = () => provider || 'default';

  return (
    <div className="pro-header-container">
      <div className="pro-header fade-in">
        <div className="pro-breadcrumbs">
          <Link to="/account-manager" className="breadcrumb-link">
            <i className="fas fa-layer-group"></i> Accounts
          </Link>
          <span className="breadcrumb-separator">/</span>
          <span className="breadcrumb-current">Add New</span>
        </div>

        <div className="pro-title-row">
          <div className="title-group">
            <h1 className="pro-page-title">Add Account</h1>
            <p className="pro-page-subtitle">{headerText}</p>
          </div>

          <div className={`provider-badge glass ${getProviderClass()}`}>
            <div className="badge-icon">
              <i className={getProviderIcon()}></i>
            </div>
            <span className="badge-text">{getProviderName()}</span>
          </div>
        </div>
      </div>
    </div>
  );
};

export default AddAccountHeader;
