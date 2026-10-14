import type { DataTransferCopy } from '../Cost/DataTransferSection';

/**
 * What the network section says on GCP, where the arithmetic is the same and the advice is not.
 *
 * <p>Kept out of the component so the shared implementation carries no provider branching, and
 * kept out of the page so the wording sits with the other GCP cost pieces.
 *
 * <p>Only the categories whose fix genuinely differs are overridden. A zone is a zone and a NAT
 * gateway is a NAT gateway, but the services worth putting behind an endpoint are not the same
 * list, and GCP's discounts are automatic where AWS's are purchased.
 */
export const GCP_NETWORK_COPY: DataTransferCopy = {
  subheading:
    'Network charges pulled out of every service they are billed under, read from the SKU on each ' +
    'billing row. Open a category to see the routes and costs behind it.',

  footnote:
    'Inbound data transfer is free on GCP — the charge sits on whatever processes it, which is why ' +
    'load balancing and Cloud NAT carry the cost of traffic coming in. Gateways, tunnels and ' +
    'forwarding rules also bill per hour whether anything flows through them. Rows costing nothing ' +
    'are still listed: free-tier and sustained-use credits offset real traffic, and the gigabytes ' +
    'are worth seeing even when the charge is zero.',

  emptyHint:
    'Either this project moved no billable traffic, or its billing export names network SKUs in a ' +
    'form this breakdown does not yet recognise. Anything unrecognised is listed under "Other ' +
    'network" rather than dropped, so an empty section here means no matching SKUs at all.',

  currentMonthHint:
    'A billing export lags the usage it describes by a few hours, so a month that has only just ' +
    'started can still be empty. Pick last month for a complete picture.',

  categoryBlurbs: {
    'NAT Gateway':
      'Private instances reaching the internet through Cloud NAT. Billed per gateway-hour and per ' +
      'GB processed. Private Google Access carries traffic to Google APIs without touching the ' +
      'gateway at all, which removes the per-GB half for that portion.',
    'VPC endpoint':
      'Private Service Connect, keeping traffic to Google and partner services off the NAT ' +
      'gateway. Compare this against the NAT Gateway row: moving traffic here is usually cheaper.',
    'Cross-AZ':
      'Traffic between zones inside one region, charged in both directions. Usually an application ' +
      'talking to a database in another zone. Co-locating the pair removes it, at the cost of the ' +
      'resilience that spreading them buys.',
    'Inter-region':
      'Traffic between regions — replication, cross-region reads, or a multi-region deployment. ' +
      'Priced on the source region, so the origin below is where the charge was incurred.',
    'Internet egress':
      'Data leaving Google for the internet. Cloud CDN or a Standard Tier route is usually cheaper ' +
      'per GB than Premium Tier for traffic that does not need the premium backbone.',
    'Internet ingress':
      'Inbound data is free on GCP, so anything appearing here is worth a look — the real cost of ' +
      'inbound traffic shows up under Load balancing and NAT Gateway instead.',
  },

  noResourceDetail: (
    <>
      This project uses GCP&apos;s standard billing export, which records no resource name, so
      network charges cannot be attributed to a specific instance, gateway or load balancer.
      Switching the export to <strong>detailed usage cost</strong> adds it from the next billing
      period — the route and SKU views work either way.
    </>
  ),
};
