import React from 'react';
import WarPage from './WarPage';

/**
 * Azure WAR — Workload Attack-surface Review.
 *
 * A route entry point rather than a page. The WAR report shape is provider-neutral, so
 * the whole implementation is shared with the AWS and GCP routes; only the baseline
 * differs.
 *
 * The Azure baseline carries 86 controls from the Azure Cloud Security Advisory. Unlike
 * the GCP workbook, it has no duplicated rows — all 86 are distinct.
 */
const AzureWarPage: React.FC = () => <WarPage provider="Azure" baselineSize={86} />;

export default AzureWarPage;
