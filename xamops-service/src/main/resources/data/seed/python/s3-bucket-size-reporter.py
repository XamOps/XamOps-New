#!/usr/bin/env python3
"""
s3-bucket-size-reporter.py
Calculates total object count and size for an S3 bucket (optionally scoped to
a prefix) by paginating over object listings.

Parameters:
  {{AWS_REGION}}  - AWS region, e.g. us-east-1
  {{BUCKET_NAME}} - S3 bucket name to measure
  {{PREFIX}}      - Optional key prefix to scope the report (empty = whole bucket)
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
PREFIX = "{{PREFIX}}"


def parse_args():
    parser = argparse.ArgumentParser(description="Report S3 bucket size.")
    parser.add_argument("--region", default=AWS_REGION, help="AWS region.")
    parser.add_argument("--bucket", default=BUCKET_NAME, help="S3 bucket name.")
    parser.add_argument("--prefix", default=PREFIX, help="Optional key prefix.")
    return parser.parse_args()


def human_size(num_bytes):
    size = float(num_bytes)
    for unit in ["B", "KB", "MB", "GB", "TB", "PB"]:
        if size < 1024.0:
            return f"{size:,.2f} {unit}"
        size /= 1024.0
    return f"{size:,.2f} EB"


def main():
    args = parse_args()
    try:
        s3 = boto3.client("s3", region_name=args.region)
        paginator = s3.get_paginator("list_objects_v2")
        total_bytes = 0
        total_objects = 0
        for page in paginator.paginate(Bucket=args.bucket, Prefix=args.prefix):
            for obj in page.get("Contents", []):
                total_bytes += obj["Size"]
                total_objects += 1
        scope = args.prefix or "(entire bucket)"
        print(f"Bucket: {args.bucket}  Prefix: {scope}")
        print(f"Objects: {total_objects:,}")
        print(f"Total size: {human_size(total_bytes)}")
        return 0
    except (BotoCoreError, ClientError) as exc:
        print(f"ERROR: AWS call failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
