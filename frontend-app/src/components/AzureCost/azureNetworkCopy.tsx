import type { DataTransferCopy } from '../Cost/DataTransferSection';

/**
 * What the network section says on Azure, where the arithmetic is the same and the advice is not.
 *
 * <p>Kept out of the component so the shared implementation carries no provider branching, and kept
 * out of the page so the wording sits with the other Azure cost pieces — the same arrangement GCP
 * uses.
 *
 * <p>Only the categories whose fix genuinely differs are overridden. A zone is a zone and a NAT
 * gateway is a NAT gateway, but Azure prices bandwidth by geography rather than by region pair, its
 * zone-to-zone traffic inside a region has been free since 2023 for most services, and its discounts
 * are reservations rather than the committed-use variety.
 */
export const AZURE_NETWORK_COPY: DataTransferCopy = {
  subheading:
    'Network charges pulled out of every service they are billed under, read from the meter on each ' +
    'billed line. Expand a row for the route and the cost on one line.',

  footnote:
    'Every route here is inferred from the meter name and the billed region: Azure Cost Management ' +
    'carries no source or destination column at any scope, so unlike an AWS report it cannot be ' +
    'asked where traffic went. Gateways, tunnels and circuits also bill per hour whether anything ' +
    'flows through them, so an idle one costs the same as a busy one. Rows costing nothing are still ' +
    'listed — the first 100 GB of egress each month is free and the gigabytes are worth seeing even ' +
    'when the charge is zero.',

  emptyHint:
    'Either this subscription moved no billable traffic, or its meters are named in a form this ' +
    'breakdown does not yet recognise. Anything unrecognised is listed under "Other network" rather ' +
    'than dropped, so an empty section here means no matching meters at all. Storage ' +
    'geo-replication traffic is included even though Azure bills it under Storage.',

  categoryBlurbs: {
    'NAT Gateway':
      'Private subnets reaching the internet through a NAT gateway. Billed per gateway-hour and per ' +
      'GB processed. A service endpoint or Private Link route removes the per-GB half for traffic to ' +
      'Azure services, and an idle gateway still bills its hours.',
    'VPC endpoint':
      'Private Link and private endpoints, keeping traffic to Azure and partner services off the NAT ' +
      'gateway and off the public internet. Compare this against the NAT Gateway row: moving traffic ' +
      'here is usually the cheaper side as well as the more private one.',
    'Cross-AZ':
      'Traffic between availability zones inside one region, and intra-region VNet peering. Azure ' +
      'stopped charging for availability-zone traffic on most services in 2023, so a large figure ' +
      'here usually means peering between virtual networks rather than zone crossing — peered VNets ' +
      'are billed in both directions.',
    'Inter-region':
      'Traffic between Azure regions — global VNet peering, cross-region replication, and ' +
      'geo-redundant storage. Priced on the source region, so the origin below is where the charge ' +
      'was incurred and the far end is not recorded.',
    'Internet egress':
      'Data leaving Azure for the internet, priced by destination geography rather than by region ' +
      'pair. The first 100 GB each month is free. Azure CDN or Front Door is usually cheaper per GB ' +
      'for traffic that can be cached.',
    'Internet ingress':
      'Inbound data is free on Azure, so anything appearing here is worth a look — the real cost of ' +
      'inbound traffic shows up under Load balancing and NAT Gateway instead.',
    'Load balancing':
      'Load balancer rules, application gateway and Front Door capacity units, firewall deployment ' +
      'hours, and the data each processes. This is where the cost of inbound traffic lands on Azure, ' +
      'because ingress itself is free and the charge sits on whatever handles it.',
    'Cloud VPN':
      'VPN gateway uptime plus the traffic crossing it. A gateway is billed per hour whether any ' +
      'tunnel is connected, so a decommissioned site still costs its SKU rate until the gateway is ' +
      'deleted.',
    Interconnect:
      'ExpressRoute circuits and their egress. On a metered circuit, egress is cheaper per GB than ' +
      'the internet, so traffic still taking the public route is worth moving here; on an unlimited ' +
      'circuit the port charge is the whole cost and per-GB rows will be empty.',
  },

  noResourceDetail: (
    <>
      Azure Cost Management would not group this subscription&apos;s spend by resource, so network
      charges cannot be attributed to a specific gateway, load balancer or storage account. Which
      dimensions are available depends on the agreement behind the subscription — the category and
      route views work either way.
    </>
  ),
};
