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
      case 'aws':
        return 'fab fa-aws';
      case 'gcp':
        return 'fab fa-google';
      case 'azure':
        return 'fab fa-microsoft';
      case 'jenkins':
        return 'fab fa-jenkins';
      default:
        return 'fas fa-cloud';
    }
  };

  return (
    <header className="add-account-header">
      <div className="header-left">
        <div className={`header-icon ${provider}`}>
          <i className={getProviderIcon()} />
        </div>
        <div>
          <h1 className="header-title">Add New Account</h1>
          <p className="header-subtitle">{headerText}</p>
        </div>
      </div>
      <Link to="/account-manager" className="back-button">
        <i className="fas fa-arrow-left" />
        Back to Accounts
      </Link>
    </header>
  );
};

export default AddAccountHeader;
