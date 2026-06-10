#!/usr/bin/env python3
"""
ec2-instance-manager.py
Start, stop, reboot, or list EC2 instances in a region using boto3.

Parameters:
  {{AWS_REGION}}   - AWS region, e.g. us-east-1
  {{INSTANCE_IDS}} - Comma-separated instance IDs, or 'all'
  {{ACTION}}       - One of: list, start, stop, reboot
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
INSTANCE_IDS = "{{INSTANCE_IDS}}"
ACTION = "{{ACTION}}"


def parse_args():
    parser = argparse.ArgumentParser(description="Manage EC2 instances (list/start/stop/reboot).")
    parser.add_argument("--region", default=AWS_REGION, help="AWS region.")
    parser.add_argument("--instance-ids", default=INSTANCE_IDS,
                        help="Comma-separated instance IDs, or 'all'.")
    parser.add_argument("--action", default=ACTION,
                        choices=["list", "start", "stop", "reboot"], help="Action to perform.")
    return parser.parse_args()


def resolve_ids(ec2, raw):
    if raw.strip().lower() == "all":
        resp = ec2.describe_instances()
        ids = []
        for reservation in resp.get("Reservations", []):
            for inst in reservation.get("Instances", []):
                ids.append(inst["InstanceId"])
        return ids
    return [i.strip() for i in raw.split(",") if i.strip()]


def list_instances(ec2):
    resp = ec2.describe_instances()
    for reservation in resp.get("Reservations", []):
        for inst in reservation.get("Instances", []):
            name = ""
            for tag in inst.get("Tags", []):
                if tag["Key"] == "Name":
                    name = tag["Value"]
            print(f"{inst['InstanceId']}\t{inst['State']['Name']}\t"
                  f"{inst.get('InstanceType', '-')}\t{name}")


def main():
    args = parse_args()
    try:
        ec2 = boto3.client("ec2", region_name=args.region)
        if args.action == "list":
            list_instances(ec2)
            return 0
        ids = resolve_ids(ec2, args.instance_ids)
        if not ids:
            print("ERROR: No instance IDs resolved.", file=sys.stderr)
            return 1
        if args.action == "start":
            ec2.start_instances(InstanceIds=ids)
        elif args.action == "stop":
            ec2.stop_instances(InstanceIds=ids)
        elif args.action == "reboot":
            ec2.reboot_instances(InstanceIds=ids)
        print(f"Action '{args.action}' issued for: {', '.join(ids)}")
        return 0
    except (BotoCoreError, ClientError) as exc:
        print(f"ERROR: AWS call failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
