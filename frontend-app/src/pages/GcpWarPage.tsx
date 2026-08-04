import React from 'react';
import WarPage from './WarPage';

/**
 * GCP WAR — Workload Attack-surface Review.
 *
 * A route entry point rather than a page. The WAR report shape is provider-neutral, so
 * the whole implementation is shared with the AWS route; only the baseline differs.
 *
 * The GCP baseline carries 87 controls, transcribed from the XamSecure GCP advisory. The
 * workbook numbers 96 rows, but its AI section was drafted twice — rows 63-71 are an
 * earlier pass superseded by the 72-88 block — so nine duplicates are dropped in
 * GcpWarControlCatalog rather than double-counted in the score.
 */
const GcpWarPage: React.FC = () => <WarPage provider="GCP" baselineSize={87} />;

export default GcpWarPage;
