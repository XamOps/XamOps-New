import React from 'react';
import WarPage from './WarPage';

/**
 * AWS WAR — Workload Attack-surface Review.
 *
 * A route entry point rather than a page; the implementation is shared with the GCP
 * route. The AWS baseline carries 86 controls from the AWS Cloud Security Advisory.
 */
const AwsWarPage: React.FC = () => <WarPage provider="AWS" baselineSize={86} />;

export default AwsWarPage;
