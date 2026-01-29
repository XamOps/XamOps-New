/**
 * AccountManagerHeader Component
 *
 * Header section of the Account Manager page with title and Add Account button
 */

import React from 'react';
import { Link } from 'react-router-dom';

interface AccountManagerHeaderProps {
  onAddAccount?: () => void;
}

const AccountManagerHeader: React.FC<AccountManagerHeaderProps> = ({ onAddAccount }) => {
  return (
    <header className="account-manager-header">
      <div className="flex items-center gap-4">
        <div className="header-icon">
          <i className="fas fa-users-cog text-white text-xl" />
        </div>
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Account Manager</h1>
          <p className="text-sm text-gray-500 mt-0.5">
            Manage your connected cloud accounts and CI/CD integrations
          </p>
        </div>
      </div>
      <div className="flex items-center gap-4">
        <div className="flex items-center gap-2">
          <span className="status-indicator active" />
          <span className="text-sm text-gray-600">Active Monitoring</span>
        </div>
        <Link
          to="/add-account"
          className="btn-primary"
          onClick={onAddAccount}
        >
          <i className="fas fa-plus mr-2" />
          Add New Cloud Account
        </Link>
      </div>
    </header>
  );
};

export default AccountManagerHeader;
