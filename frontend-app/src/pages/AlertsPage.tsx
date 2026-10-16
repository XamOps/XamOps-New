/**
 * AlertsPage Component
 *
 * Main AWS Alerts page
 * Converted from: alerts.html
 *
 * The page body lives in the reusable <AlertsTabContent> component, which is shared with the
 * Security Center's "Alerts" sub-tab. This page simply renders it as the whole page, so the
 * standalone /alerts route (and deep links into it) behave exactly as before.
 *
 * Features:
 * - CloudGuard Alerts tab, one section per kind of alert: service limits (one row per limit, a
 *   chip per region), security findings, and cost spikes
 * - K8s Alerts tab aggregating EKS cluster alerts
 * - KPI cards showing alert severity counts and when the list was built
 * - Severity filter
 *
 * API Endpoints:
 * - GET /api/xamops/cloudguard/alerts?accountId={accountId}&forceRefresh={boolean}
 */

import React from 'react';
import { AlertsTabContent } from '../components/Alerts';

const AlertsPage: React.FC = () => {
  return (
    <main className="flex-1 px-6 pt-4 pb-6 bg-white min-h-screen">
      <AlertsTabContent isActive={true} />
    </main>
  );
};

export default AlertsPage;
