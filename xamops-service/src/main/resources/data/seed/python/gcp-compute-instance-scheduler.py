#!/usr/bin/env python3
"""
gcp-compute-instance-scheduler.py
Starts or stops a Google Compute Engine instance to support cost-saving
start/stop schedules (e.g. via cron or Cloud Scheduler).

Parameters:
  {{PROJECT_ID}}    - GCP project ID
  {{GCP_ZONE}}      - Compute Engine zone, e.g. us-central1-a
  {{INSTANCE_NAME}} - Name of the instance to manage
  {{ACTION}}        - One of: start, stop
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
INSTANCE_NAME = "{{INSTANCE_NAME}}"
ACTION = "{{ACTION}}"


def parse_args():
    parser = argparse.ArgumentParser(description="Start/stop a GCE instance.")
    parser.add_argument("--project-id", default=PROJECT_ID, help="GCP project ID.")
    parser.add_argument("--zone", default=GCP_ZONE, help="Compute Engine zone.")
    parser.add_argument("--instance-name", default=INSTANCE_NAME, help="Instance name.")
    parser.add_argument("--action", default=ACTION, choices=["start", "stop"],
                        help="Action to perform.")
    return parser.parse_args()


def main():
    args = parse_args()
    try:
        client = compute_v1.InstancesClient()
        if args.action == "start":
            op = client.start(project=args.project_id, zone=args.zone,
                              instance=args.instance_name)
        else:
            op = client.stop(project=args.project_id, zone=args.zone,
                             instance=args.instance_name)
        print(f"Issued '{args.action}' for {args.instance_name} in {args.zone}.")
        print(f"Operation: {op.name if hasattr(op, 'name') else 'submitted'}")
        return 0
    except GoogleAPIError as exc:
        print(f"ERROR: GCP call failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
