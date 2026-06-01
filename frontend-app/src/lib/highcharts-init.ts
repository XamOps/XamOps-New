import Highcharts from 'highcharts';
import * as mapInitModule from 'highcharts/modules/map';
import * as exportingInitModule from 'highcharts/modules/exporting';
import * as highcharts3DModule from 'highcharts/highcharts-3d';
import * as variablePieModule from 'highcharts/modules/variable-pie';

const applyModule = (mod: any) => {
  const fn = mod?.default ?? mod;
  if (typeof fn === 'function') fn(Highcharts);
};

applyModule(mapInitModule);
applyModule(exportingInitModule);
applyModule(highcharts3DModule);
applyModule(variablePieModule);

if (typeof globalThis !== 'undefined') {
  (globalThis as any).Highcharts = Highcharts;
}

export default Highcharts;
