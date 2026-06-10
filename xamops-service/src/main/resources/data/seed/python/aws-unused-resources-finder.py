#!/usr/bin/env python3
"""
aws-unused-resources-finder.py
Scans a region for cost-wasting unused resources: unattached EBS volumes,
unassociated Elastic IPs, and stopped EC2 instances.

Parameters:
  {{AWS_REGION}} - AWS region to scan, e.g. us-east-1
"""
import argparse
import sys

try:
    import boto3
    from botocore.exceptions import BotoCoreError, ClientError
except ImportError:
    print("ERROR: boto3 is required. Install with 'pip install boto3'.", file=sys.stderr)
    sys.exit(1)

AWS_REGION = "{{AWS_REGION}}"


def parse_args():
    parser = argparse.ArgumentParser(description="Find unused AWS resources.")
    parser.add_argument("--region", default=AWS_REGION, help="AWS region.")
    return parser.parse_args()


def find_unattached_volumes(ec2):
    resp = ec2.describe_volumes(Filters=[{"Name": "status", "Values": ["available"]}])
    return [v["VolumeId"] for v in resp.get("Volumes", [])]


def find_unassociated_eips(ec2):
    resp = ec2.describe_addresses()
    return [a["PublicIp"] for a in resp.get("Addresses", []) if "AssociationId" not in a]


def find_stopped_instances(ec2):
    resp = ec2.describe_instances(
        Filters=[{"Name": "instance-state-name", "Values": ["stopped"]}])
    ids = []
    for reservation in resp.get("Reservations", []):
        for inst in reservation.get("Instances", []):
            ids.append(inst["InstanceId"])
    return ids


def main():
    args = parse_args()
    try:
        ec2 = boto3.client("ec2", region_name=args.region)
        volumes = find_unattached_volumes(ec2)
        eips = find_unassociated_eips(ec2)
        stopped = find_stopped_instances(ec2)
        print(f"Region: {args.region}")
        print(f"Unattached EBS volumes ({len(volumes)}): {', '.join(volumes) or 'none'}")
        print(f"Unassociated Elastic IPs ({len(eips)}): {', '.join(eips) or 'none'}")
        print(f"Stopped EC2 instances ({len(stopped)}): {', '.join(stopped) or 'none'}")
        return 0
    except (BotoCoreError, ClientError) as exc:
        print(f"ERROR: AWS call failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
