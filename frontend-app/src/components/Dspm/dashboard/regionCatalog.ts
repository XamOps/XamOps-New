import type { DashboardRegion } from '../../../types/dspmPages';

/**
 * Region names and map positions for the Dashboard's Active Sensitive Regions (brief 11, checked in build 8).
 *
 * The console reads both from `/data/security/regions/overview`, which our key cannot call (401). That route
 * keys its regions by a place name and lists the cloud region codes behind each (`region_cloud_mapping`), so
 * codes of different clouds in one place share a tab. The console names AWS `ap-south-1` and GCP
 * `asia-south1` both "Mumbai", GCP `us-east1` "South Carolina", GCP `us` "USA" and Azure `westeurope`
 * "Amsterdam" (checked in build 8). The names here follow that pattern:
 * - AWS: the console bundle's labels (`index-20241445.js`, `sG`) without the code ("N. Virginia (us-east-1)").
 * - GCP: the place in the bundle's labels (`aG`, "us-east1 (Moncks Corner, South Carolina, North America)"):
 *   the state for a US region, otherwise the city; the AWS name where both clouds share a metro.
 * - Azure: the city of the region's published location, the state for a US region; the AWS name where both
 *   clouds share a metro.
 * Any other code shows as itself. Positions are the regions' public locations (latitude, longitude); a GCP
 * multi-region sits at the middle of its area. An unknown code gets a tab but no map dot.
 */

const REGION_NAMES: Readonly<Record<string, string>> = {
  // AWS
  'ap-south-1': 'Mumbai',
  'ap-northeast-1': 'Tokyo',
  'ap-northeast-2': 'Seoul',
  'ap-northeast-3': 'Osaka',
  'ap-southeast-1': 'Singapore',
  'ap-southeast-2': 'Sydney',
  'ca-central-1': 'Central',
  'eu-west-1': 'Ireland',
  'eu-central-1': 'Frankfurt',
  'eu-west-2': 'London',
  'eu-west-3': 'Paris',
  'eu-north-1': 'Stockholm',
  'sa-east-1': 'São Paulo',
  'us-east-1': 'N. Virginia',
  'us-east-2': 'Ohio',
  'us-west-1': 'N. California',
  'us-west-2': 'Oregon',
  'af-south-1': 'Cape Town',
  'ap-east-1': 'Hong Kong',
  'ap-south-2': 'Hyderabad',
  'ap-southeast-3': 'Jakarta',
  'ap-southeast-4': 'Melbourne',
  'eu-south-1': 'Milan',
  'eu-south-2': 'Spain',
  'eu-central-2': 'Zurich',
  'me-south-1': 'Bahrain',
  'me-central-1': 'UAE',
  'il-central-1': 'Israel',
  // GCP
  'us-east1': 'South Carolina',
  'us-east4': 'N. Virginia',
  'us-central1': 'Iowa',
  'us-west1': 'Oregon',
  'europe-west4': 'Eemshaven',
  'europe-west1': 'St. Ghislain',
  'europe-west3': 'Frankfurt',
  'europe-west2': 'London',
  'asia-east1': 'Changhua County',
  'asia-southeast1': 'Singapore',
  'asia-northeast1': 'Tokyo',
  'asia-south1': 'Mumbai',
  'australia-southeast1': 'Sydney',
  'southamerica-east1': 'São Paulo',
  'asia-east2': 'Hong Kong',
  'asia-northeast2': 'Osaka',
  'asia-northeast3': 'Seoul',
  'asia-south2': 'Delhi',
  'asia-southeast2': 'Jakarta',
  'australia-southeast2': 'Melbourne',
  'europe-central2': 'Warsaw',
  'europe-north1': 'Hamina',
  'europe-southwest1': 'Madrid',
  'europe-west12': 'Turin',
  'europe-west6': 'Zurich',
  'europe-west8': 'Milan',
  'europe-west9': 'Paris',
  'me-central1': 'Doha',
  'me-west1': 'Tel Aviv',
  'northamerica-northeast1': 'Montréal',
  'northamerica-northeast2': 'Toronto',
  'southamerica-west1': 'Santiago',
  'us-east5': 'Ohio',
  'us-south1': 'Texas',
  'us-west2': 'California',
  'us-west3': 'Utah',
  'us-west4': 'Nevada',
  us: 'USA',
  asia: 'Asia Pacific',
  europe: 'Europe',
  // Azure
  centralindia: 'Pune',
  southindia: 'Chennai',
  westindia: 'Mumbai',
  australiaeast: 'Sydney',
  australiasoutheast: 'Melbourne',
  eastus: 'Virginia',
  eastus2: 'Virginia',
  westus: 'California',
  westus2: 'Washington',
  northcentralus: 'Illinois',
  southcentralus: 'Texas',
  canadacentral: 'Toronto',
  canadaeast: 'Québec City',
  westeurope: 'Amsterdam',
  northeurope: 'Ireland',
  uksouth: 'London',
  ukwest: 'Cardiff',
  germanywestcentral: 'Frankfurt',
  germanynorth: 'Berlin',
  francecentral: 'Paris',
  francesouth: 'Marseille',
  norwayeast: 'Oslo',
  norwaywest: 'Stavanger',
  swedencentral: 'Gävle',
  swedensouth: 'Malmö',
  switzerlandnorth: 'Zurich',
  switzerlandwest: 'Geneva',
  southafricanorth: 'Johannesburg',
  southafricawest: 'Cape Town',
  uaenorth: 'Dubai',
  uaecentral: 'Abu Dhabi',
  israelcentral: 'Israel',
  japaneast: 'Tokyo',
  japanwest: 'Osaka',
  koreacentral: 'Seoul',
  koreasouth: 'Busan',
  southeastasia: 'Singapore',
  eastasia: 'Hong Kong',
  centralus: 'Iowa',
  brazilsouth: 'São Paulo',
};

/** [latitude, longitude] of each region in the bundle's AWS, GCP and Azure lists, and of the GCP multi-regions. */
const REGION_POSITIONS: Readonly<Record<string, readonly [number, number]>> = {
  // AWS
  'ap-south-1': [19.08, 72.88],
  'ap-northeast-1': [35.68, 139.69],
  'ap-northeast-2': [37.57, 126.98],
  'ap-northeast-3': [34.69, 135.5],
  'ap-southeast-1': [1.35, 103.82],
  'ap-southeast-2': [-33.87, 151.21],
  'ca-central-1': [45.5, -73.57],
  'eu-west-1': [53.35, -6.26],
  'eu-central-1': [50.11, 8.68],
  'eu-west-2': [51.51, -0.13],
  'eu-west-3': [48.86, 2.35],
  'eu-north-1': [59.33, 18.07],
  'sa-east-1': [-23.55, -46.63],
  'us-east-1': [39.04, -77.49],
  'us-east-2': [39.96, -83.0],
  'us-west-1': [37.77, -122.42],
  'us-west-2': [45.84, -119.7],
  'af-south-1': [-33.92, 18.42],
  'ap-east-1': [22.32, 114.17],
  'ap-south-2': [17.39, 78.49],
  'ap-southeast-3': [-6.21, 106.85],
  'ap-southeast-4': [-37.81, 144.96],
  'eu-south-1': [45.46, 9.19],
  'eu-south-2': [41.65, -0.89],
  'eu-central-2': [47.38, 8.54],
  'me-south-1': [26.07, 50.56],
  'me-central-1': [25.2, 55.27],
  'il-central-1': [32.09, 34.78],
  // GCP
  'us-east1': [33.2, -80.01],
  'us-east4': [39.04, -77.49],
  'us-central1': [41.26, -95.86],
  'us-west1': [45.59, -121.18],
  'europe-west4': [53.44, 6.83],
  'europe-west1': [50.45, 3.82],
  'europe-west3': [50.11, 8.68],
  'europe-west2': [51.51, -0.13],
  'asia-east1': [24.05, 120.52],
  'asia-southeast1': [1.34, 103.71],
  'asia-northeast1': [35.68, 139.69],
  'asia-south1': [19.08, 72.88],
  'australia-southeast1': [-33.87, 151.21],
  'southamerica-east1': [-23.53, -46.79],
  'asia-east2': [22.32, 114.17],
  'asia-northeast2': [34.69, 135.5],
  'asia-northeast3': [37.57, 126.98],
  'asia-south2': [28.61, 77.21],
  'asia-southeast2': [-6.21, 106.85],
  'australia-southeast2': [-37.81, 144.96],
  'europe-central2': [52.23, 21.01],
  'europe-north1': [60.57, 27.2],
  'europe-southwest1': [40.42, -3.7],
  'europe-west12': [45.07, 7.69],
  'europe-west6': [47.38, 8.54],
  'europe-west8': [45.46, 9.19],
  'europe-west9': [48.86, 2.35],
  'me-central1': [25.29, 51.53],
  'me-west1': [32.09, 34.78],
  'northamerica-northeast1': [45.5, -73.57],
  'northamerica-northeast2': [43.65, -79.38],
  'southamerica-west1': [-33.45, -70.67],
  'us-east5': [39.96, -83.0],
  'us-south1': [32.78, -96.8],
  'us-west2': [34.05, -118.24],
  'us-west3': [40.76, -111.89],
  'us-west4': [36.17, -115.14],
  us: [39.83, -98.58],
  asia: [34.05, 100.62],
  europe: [54.53, 15.26],
  // Azure
  centralindia: [18.58, 73.92],
  southindia: [12.98, 80.16],
  westindia: [19.09, 72.87],
  australiaeast: [-33.86, 151.21],
  australiasoutheast: [-37.81, 144.96],
  eastus: [37.37, -79.82],
  eastus2: [36.67, -78.39],
  westus: [37.78, -122.42],
  westus2: [47.23, -119.85],
  northcentralus: [41.88, -87.63],
  southcentralus: [29.42, -98.5],
  canadacentral: [43.65, -79.38],
  canadaeast: [46.82, -71.22],
  westeurope: [52.37, 4.9],
  northeurope: [53.35, -6.26],
  uksouth: [50.94, -0.8],
  ukwest: [53.43, -3.08],
  germanywestcentral: [50.11, 8.68],
  germanynorth: [53.07, 8.81],
  francecentral: [46.38, 2.37],
  francesouth: [43.83, 2.2],
  norwayeast: [59.91, 10.75],
  norwaywest: [58.97, 5.73],
  swedencentral: [60.67, 17.14],
  swedensouth: [55.61, 13.0],
  switzerlandnorth: [47.45, 8.56],
  switzerlandwest: [46.2, 6.14],
  southafricanorth: [-25.73, 28.22],
  southafricawest: [-34.08, 18.84],
  uaenorth: [25.27, 55.32],
  uaecentral: [24.47, 54.37],
  israelcentral: [31.27, 33.45],
  japaneast: [35.68, 139.77],
  japanwest: [34.69, 135.5],
  koreacentral: [37.57, 126.98],
  koreasouth: [35.18, 129.08],
  southeastasia: [1.28, 103.83],
  eastasia: [22.27, 114.19],
  centralus: [41.59, -93.62],
  brazilsouth: [-23.55, -46.63],
};

/** The region's tab and card name (see the file comment); the raw code when the catalogue lacks it. */
export function regionName(code: string): string {
  return REGION_NAMES[code] ?? code;
}

/** The region's map position as react-simple-maps takes it, [longitude, latitude]; null when unknown. */
export function regionCoordinates(code: string): [number, number] | null {
  const position = REGION_POSITIONS[code];
  return position ? [position[1], position[0]] : null;
}

/** One tab of the widget: the regions the console shows under one name, summed. */
export interface RegionGroup {
  /** The first code's: the tab id and the React key. */
  id: string;
  name: string;
  /** In response order; "View All" filters the Datastores page by all of them. */
  codes: string[];
  datastores: number;
  scannedDatastores: number;
  /** The codes' labels, each once, in the order first seen. */
  sensitiveLabels: string[];
  /** The first code with a known position, [longitude, latitude]; null draws no dot. */
  coordinates: [number, number] | null;
}

/** The response's regions merged by name, each group where its first code is (the console's order). */
export function groupRegions(regions: DashboardRegion[]): RegionGroup[] {
  const groups = new Map<string, RegionGroup>();
  for (const region of regions) {
    const name = regionName(region.region);
    let group = groups.get(name);
    if (!group) {
      group = { id: region.region, name, codes: [], datastores: 0, scannedDatastores: 0, sensitiveLabels: [], coordinates: null };
      groups.set(name, group);
    }
    group.codes.push(region.region);
    group.datastores += region.datastores;
    group.scannedDatastores += region.scannedDatastores;
    for (const label of region.sensitiveLabels) {
      if (!group.sensitiveLabels.includes(label)) group.sensitiveLabels.push(label);
    }
    group.coordinates ??= regionCoordinates(region.region);
  }
  return [...groups.values()];
}
