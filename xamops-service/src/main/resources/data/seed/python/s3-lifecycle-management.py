#!/usr/bin/env python3
"""
s3-lifecycle-management.py
Applies a lifecycle policy to an S3 bucket: transition objects to a colder
storage class after N days and expire them after M days.

Parameters:
  {{AWS_REGION}}      - AWS region, e.g. us-east-1
  {{BUCKET_NAME}}     - Target S3 bucket name
  {{TRANSITION_DAYS}} - Days before transitioning to STANDARD_IA
  {{EXPIRATION_DAYS}} - Days before object expiration
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
BUCKET_NAME = "{{BUCKET_NAME}}"
TRANSITION_DAYS = "{{TRANSITION_DAYS}}"
EXPIRATION_DAYS = "{{EXPIRATION_DAYS}}"


def parse_args():
    parser = argparse.ArgumentParser(description="Manage S3 bucket lifecycle policy.")
    parser.add_argument("--region", default=AWS_REGION, help="AWS region.")
    parser.add_argument("--bucket", default=BUCKET_NAME, help="S3 bucket name.")
    parser.add_argument("--transition-days", type=int, default=int(TRANSITION_DAYS),
                        help="Days before transition to STANDARD_IA.")
    parser.add_argument("--expiration-days", type=int, default=int(EXPIRATION_DAYS),
                        help="Days before expiration.")
    return parser.parse_args()


def build_rule(transition_days, expiration_days):
    rule = {
        "ID": "xamops-lifecycle",
        "Status": "Enabled",
        "Filter": {"Prefix": ""},
        "Transitions": [
            {"Days": transition_days, "StorageClass": "STANDARD_IA"}
        ],
        "Expiration": {"Days": expiration_days},
    }
    return {"Rules": [rule]}


def main():
    args = parse_args()
    if args.expiration_days <= args.transition_days:
        print("ERROR: expiration-days must be greater than transition-days.", file=sys.stderr)
        return 1
    try:
        s3 = boto3.client("s3", region_name=args.region)
        config = build_rule(args.transition_days, args.expiration_days)
        s3.put_bucket_lifecycle_configuration(
            Bucket=args.bucket, LifecycleConfiguration=config)
        print(f"Lifecycle policy applied to '{args.bucket}': "
              f"transition after {args.transition_days}d, "
              f"expire after {args.expiration_days}d.")
        return 0
    except (BotoCoreError, ClientError) as exc:
        print(f"ERROR: AWS call failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
