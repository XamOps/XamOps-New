// Highcharts global initialization
// This file sets up Highcharts on the window object for components that expect it globally

import Highcharts from 'highcharts';
import mapInit from 'highcharts/modules/map';
import exportingInit from 'highcharts/modules/exporting';

// Initialize map module
(mapInit as any)(Highcharts);

// Initialize exporting module
(exportingInit as any)(Highcharts);

// Make Highcharts available globally for legacy components
if (typeof globalThis !== 'undefined') {
    (globalThis as any).Highcharts = Highcharts;
}

export default Highcharts;
