#!/usr/bin/env python3
"""
iam-key-rotation-auditor.py
Audits IAM users' access keys and flags any active key older than a maximum
allowed age, helping enforce key-rotation policy.

Parameters:
  {{MAX_KEY_AGE_DAYS}} - Maximum acceptable access-key age in days
"""
import argparse
import sys
from datetime import datetime, timezone

try:
    import boto3
    from botocore.exceptions import BotoCoreError, ClientError
except ImportError:
    print("ERROR: boto3 is required. Install with 'pip install boto3'.", file=sys.stderr)
    sys.exit(1)

MAX_KEY_AGE_DAYS = "{{MAX_KEY_AGE_DAYS}}"


def parse_args():
    parser = argparse.ArgumentParser(description="Audit IAM access key age.")
    parser.add_argument("--max-key-age-days", type=int, default=int(MAX_KEY_AGE_DAYS),
                        help="Maximum acceptable key age in days.")
    return parser.parse_args()


def main():
    args = parse_args()
    now = datetime.now(timezone.utc)
    stale = 0
    try:
        iam = boto3.client("iam")
        user_paginator = iam.get_paginator("list_users")
        for page in user_paginator.paginate():
            for user in page.get("Users", []):
                username = user["UserName"]
                keys = iam.list_access_keys(UserName=username).get("AccessKeyMetadata", [])
                for key in keys:
                    if key["Status"] != "Active":
                        continue
                    age_days = (now - key["CreateDate"]).days
                    if age_days > args.max_key_age_days:
                        stale += 1
                        print(f"STALE: {username} key {key['AccessKeyId']} "
                              f"is {age_days} days old")
        print(f"\nFound {stale} key(s) older than {args.max_key_age_days} days.")
        return 1 if stale else 0
    except (BotoCoreError, ClientError) as exc:
        print(f"ERROR: AWS call failed: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
