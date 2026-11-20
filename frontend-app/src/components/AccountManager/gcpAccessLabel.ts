/** What a GCP project's stored access type allows, for the account card. Null when not checked yet. */
export function gcpAccessLabel(type?: string | null): string | null {
  switch (type) {
    case 'READ_ONLY':
      return 'Read-only: AutoSpotting can plan but not convert';
    case 'AUTOSPOTTING':
      return 'AutoSpotting: can convert instance groups to Spot';
    case 'READ_WRITE':
      return 'Read-write: AutoSpotting plus start, stop and resize';
    default:
      return null;
  }
}
