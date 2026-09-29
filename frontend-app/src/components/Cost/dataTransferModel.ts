import type { TransferCharge, TransferFlow } from '../../api/curInsightsApi';

/**
 * Turns the two grains the report can answer at into the four shapes a reader asks for.
 *
 * <p>Kept out of the component because the arithmetic is the substance of the section, not
 * its presentation: which rows roll up from which grain decides whether a total is exact or
 * capped, and that is a correctness question, not a layout one.
 *
 * <h2>Which grain feeds which view</h2>
 * <ul>
 *   <li><b>Routes</b> roll up from {@code charges}, which is uncapped, so a route total is
 *       the real one.</li>
 *   <li><b>Flows and resources</b> roll up from {@code flows}, which is capped per category
 *       to keep the payload finite. Their totals are therefore of the listed rows, and each
 *       carries {@code categoryFlows} so the UI can say what it is leaving out instead of
 *       presenting a short total as a complete one.</li>
 * </ul>
 */

/** A location pair, normalised so the same route from either grain lands on one row. */
const routeKey = (from: string, to: string): string => `${from}\u0000${to}`;

/** GB and hours are never added together: a gateway bills both and their sum means nothing. */
export interface Quantities {
  gigabytes: number;
  hours: number;
}

const GB = (unit: string): boolean => unit.toUpperCase() === 'GB';
const HOURS = (unit: string): boolean => ['HRS', 'HOURS', 'HOUR'].includes(unit.toUpperCase());

/** One from → to pair inside a category, with everything charged against it. */
export interface Route extends Quantities {
  from: string;
  to: string;
  cost: number;
  /** True when every charge on this route had its route inferred from the usage type. */
  derived: boolean;
  /** Which services moved the bytes, costliest first. */
  services: string[];
}

/** One resource's total network spend inside a category, across all its routes. */
export interface Resource extends Quantities {
  resourceId: string;
  name: string;
  service: string;
  region: string;
  availabilityZone: string;
  cost: number;
  /** Routes this resource was charged on, costliest first. */
  routes: { from: string; to: string; cost: number }[];
}

export interface Group {
  category: string;
  /** The category total, from the uncapped billing grain. The header number. */
  cost: number;
  charges: TransferCharge[];
  flows: TransferFlow[];
  routes: Route[];
  resources: Resource[];
  /** Regions the category was billed in, costliest first. */
  regions: string[];
  /** Total GB, when the category is measured in GB at all. Null for hour-only categories. */
  gigabytes: number | null;
  /** Charges with no route at all, in dollars. Zero when everything is routed. */
  unroutedCost: number;
  /** Every flow in the category, not only the listed ones. Zero when flows are unavailable. */
  categoryFlows: number;
}

/** Sorts by cost, descending. */
const byCost = <T extends { cost: number }>(a: T, b: T): number => b.cost - a.cost;

/** Keys of a map, ordered by the accumulated value, descending. */
const rankedKeys = (weights: Map<string, number>): string[] =>
  Array.from(weights.entries())
    .sort((a, b) => b[1] - a[1])
    .map(([key]) => key);

const buildRoutes = (charges: TransferCharge[]): { routes: Route[]; unroutedCost: number } => {
  const acc = new Map<string, Route & { serviceCost: Map<string, number>; reported: boolean }>();
  let unroutedCost = 0;

  for (const c of charges) {
    if (!c.fromLocation && !c.toLocation) {
      unroutedCost += c.cost;
      continue;
    }
    const key = routeKey(c.fromLocation, c.toLocation);
    let route = acc.get(key);
    if (!route) {
      route = {
        from: c.fromLocation,
        to: c.toLocation,
        cost: 0,
        gigabytes: 0,
        hours: 0,
        derived: true,
        reported: false,
        services: [],
        serviceCost: new Map(),
      };
      acc.set(key, route);
    }
    route.cost += c.cost;
    if (GB(c.unit)) route.gigabytes += c.quantity;
    if (HOURS(c.unit)) route.hours += c.quantity;
    // One first-hand charge is enough to stop calling the whole route an inference.
    if (!c.routeDerived) route.reported = true;
    route.serviceCost.set(c.service, (route.serviceCost.get(c.service) ?? 0) + c.cost);
  }

  const routes = Array.from(acc.values())
    .map(({ serviceCost, reported, ...route }) => ({
      ...route,
      derived: !reported,
      services: rankedKeys(serviceCost),
    }))
    .sort(byCost);

  return { routes, unroutedCost };
};

const buildResources = (flows: TransferFlow[]): Resource[] => {
  const acc = new Map<string, Resource & { routeCost: Map<string, number> }>();

  for (const f of flows) {
    // Service and region join the key because they join the backend's grain: two rows with
    // the same id in different services are two rows, and merging them here would invent a
    // resource that the report does not describe.
    const key = `${f.resourceId}\u0000${f.service}\u0000${f.region}`;
    let resource = acc.get(key);
    if (!resource) {
      resource = {
        resourceId: f.resourceId,
        name: f.name,
        service: f.service,
        region: f.region,
        availabilityZone: f.availabilityZone,
        cost: 0,
        gigabytes: 0,
        hours: 0,
        routes: [],
        routeCost: new Map(),
      };
      acc.set(key, resource);
    }
    resource.cost += f.cost;
    resource.gigabytes += f.gigabytes;
    resource.hours += f.hours;
    if (!resource.availabilityZone && f.availabilityZone) {
      resource.availabilityZone = f.availabilityZone;
    }
    if (!resource.name && f.name) resource.name = f.name;
    if (f.fromLocation || f.toLocation) {
      const key2 = routeKey(f.fromLocation, f.toLocation);
      resource.routeCost.set(key2, (resource.routeCost.get(key2) ?? 0) + f.cost);
    }
  }

  return Array.from(acc.values())
    .map(({ routeCost, ...resource }) => ({
      ...resource,
      routes: rankedKeys(routeCost).map((key) => {
        const [from, to] = key.split('\u0000');
        return { from, to, cost: routeCost.get(key) ?? 0 };
      }),
    }))
    .sort(byCost);
};

/** Groups a breakdown into one entry per network category, costliest first. */
export const buildGroups = (
  charges: TransferCharge[],
  flows: TransferFlow[],
): Group[] => {
  const chargesBy = new Map<string, TransferCharge[]>();
  for (const c of charges) {
    const list = chargesBy.get(c.category);
    if (list) list.push(c);
    else chargesBy.set(c.category, [c]);
  }

  const flowsBy = new Map<string, TransferFlow[]>();
  for (const f of flows) {
    const list = flowsBy.get(f.category);
    if (list) list.push(f);
    else flowsBy.set(f.category, [f]);
  }

  return Array.from(chargesBy.entries())
    .map(([category, categoryCharges]) => {
      const categoryFlows = (flowsBy.get(category) ?? []).slice().sort(byCost);
      const { routes, unroutedCost } = buildRoutes(categoryCharges);
      const gigabytes = categoryCharges
        .filter((c) => GB(c.unit))
        .reduce((sum, c) => sum + c.quantity, 0);

      const regionCost = new Map<string, number>();
      for (const c of categoryCharges) {
        regionCost.set(c.region, (regionCost.get(c.region) ?? 0) + c.cost);
      }

      return {
        category,
        cost: categoryCharges.reduce((sum, c) => sum + c.cost, 0),
        charges: categoryCharges.slice().sort(byCost),
        flows: categoryFlows,
        routes,
        resources: buildResources(categoryFlows),
        regions: rankedKeys(regionCost),
        gigabytes: gigabytes > 0 ? gigabytes : null,
        unroutedCost,
        categoryFlows: categoryFlows[0]?.categoryFlows ?? 0,
      };
    })
    .sort(byCost);
};
