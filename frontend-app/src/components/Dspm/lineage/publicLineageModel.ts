/**
 * The public path's lineage lanes (build 7 design 6.4). Pure. Both kinds go through build 3's
 * `buildEndpointLineageLanes`: an endpoint file as the Endpoint file drawer draws it, a drive file with
 * its owner e-mail as the caption and First Seen actor, the drive's service as the channel, and the Google
 * Drive logo on its own lane.
 */

import type { PublicFileLineage } from '../../../types/dspmLineage';
import { driveIconKey } from '../datastores/datastoreVisuals';
import { OWN_LANE_KEY, buildEndpointLineageLanes } from '../datastores/endpoint/endpointLineageModel';
import type { LineageLaneView } from '../datastores/saas/lineageModel';
import { serviceLabel } from './lineageVisuals';

/** What the lanes need of the chosen file and datastore rows; nulls when they are not on the loaded page. */
export interface PublicLineageSubject {
  /** The file's name, else '-'. */
  name: string;
  /** The datastore's name: an endpoint file's channel. */
  datastoreName: string | null;
  /** The datastore's service: a drive file's channel and own-lane icon. */
  service: string | null;
  /** The file row's actor (endpoint). */
  actor: string | null;
  /** The file row's creation time (endpoint): "File Created". */
  createdAt: number | null;
}

/** The lanes to draw; none while the lineage loads or after it failed. */
export function publicLineageLanes(
  kind: 'ENDPOINT' | 'DRIVE', subject: PublicLineageSubject, lineage: PublicFileLineage | null,
): LineageLaneView[] {
  if (!lineage) return [];
  const files = { asset: lineage.asset, copies: lineage.copies };
  if (kind === 'ENDPOINT') {
    return buildEndpointLineageLanes(
      { name: subject.name, owner: subject.actor, device: subject.datastoreName, fileCreatedAt: subject.createdAt }, files);
  }
  const lanes = buildEndpointLineageLanes(
    { name: subject.name, owner: lineage.ownerEmail, device: serviceLabel(subject.service), fileCreatedAt: null }, files);
  const icon = driveIconKey(subject.service === 'GOOGLE_DRIVE' ? 'GOOGLE_DRIVE' : null);
  return lanes.map(lane => (lane.key === OWN_LANE_KEY ? { ...lane, icon } : lane));
}
