#!/usr/bin/env python3
"""
gcp-unused-disks-finder.py
Finds unattached (orphaned) persistent disks in a GCP zone that are no longer
in use by any instance, helping reclaim storage cost.

Parameters:
  {{PROJECT_ID}} - GCP project ID
  {{GCP_ZONE}}   - Compute Engine zone, e.g. us-central1-a
"""
import argparse
import sys

try:
    from google.cloud import compute_v1
    from google.api_core.exceptions import GoogleAPIError
except ImportError:
    print("ERROR: google-cloud-compute required. Install 'google-cloud-compute'.",
          file=sys.stderr)
    sys.exit(1)

PROJECT_ID = "{{PROJECT_ID}}"
GCP_ZONE = "{{GCP_ZONE}}"


def parse_args():
    parser = argparse.ArgumentParser(description="Find unattached GCP persistent disks.")
    parser.add_argument("--project-id", default=PROJECT_ID, help="GCP project ID.")
    parser.add_argument("--zone", default=GCP_ZONE, help="Compute Engine zone.")
    return parser.parse_args()


def main():
    args = parse_args()
    try:
        client = compute_v1.DisksClient()
        orphans = []
        total_gb = 0
        for disk in client.list(project=args.project_id, zone=args.zone):
            if not disk.users:
                orphans.append(disk.name)
                total_gb += disk.size_gb
        print(f"Project: {args.project_id}  Zone: {args.zone}")
        print(f"Unattached disks ({len(orphans)}): {', '.join(orphans) or 'none'}")
        print(f"Reclaimable capacity: {total_gb} GB")
        return 0
    except GoogleAPIError as exc:
        print(f"ERROR: GCP call failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
